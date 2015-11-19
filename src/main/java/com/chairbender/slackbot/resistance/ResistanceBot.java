package com.chairbender.slackbot.resistance;

import com.chairbender.slackbot.resistance.game.model.Player;
import com.chairbender.slackbot.resistance.game.model.PlayerCharacter;
import com.chairbender.slackbot.resistance.game.state.PickTeamState;
import com.chairbender.slackbot.resistance.game.state.PreGameState;
import com.chairbender.slackbot.resistance.util.GameMessageUtil;
import com.ullink.slack.simpleslackapi.SlackChannel;
import com.ullink.slack.simpleslackapi.SlackMessageHandle;
import com.ullink.slack.simpleslackapi.SlackSession;
import com.ullink.slack.simpleslackapi.SlackUser;
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted;
import com.ullink.slack.simpleslackapi.listeners.SlackMessagePostedListener;
import com.ullink.slack.simpleslackapi.replies.SlackChannelReply;

import java.util.Collections;
import java.util.Set;

/**
 * Encapsulates all of the logic of the bot.
 *
 *
 * Created by chairbender on 11/18/2015.
 */
//TODO: Develop a "test mode" where one person can play all the roles. Ignore actual usernames, use the first
    //word in the sentence as the actual username
public class ResistanceBot {
    private String botName;
    private SlackSession session;
    private BotState state;
    //tracks the last message
    private String lastMessage;
    //tracks the players in the game
    private Set<String> playerUsernames;
    //tracks the channel the game was started in
    private SlackChannel gameChannel;
    private enum BotState {
        WAITING_TO_START,
        REGISTRATION
    }

    //All the various game states. Looks messy, but it works. Each state is its own, self-contained
    //game situation. They can have arbitrary action methods. We could create a single Object and then cast
    //it to the appropriate state when checking, but it's more readable to just have typed objects even
    //though it clutters up our field definitions
    PickTeamState pickTeamState;

    /**
     *
     * @param session session to use to connect to slack
     * @param botName name of the bot configured in slack
     */
    public ResistanceBot(SlackSession session, String botName) {
        this.botName = botName;
        this.session = session;
        this.state = BotState.WAITING_TO_START;
    }

    /**
     * Starts running and listening for messages
     */
    public void run() {
        session.addMessagePostedListener(new SlackMessagePostedListener() {
            @Override
            public void onEvent(SlackMessagePosted event, SlackSession session) {
                String message = event.getMessageContent();
                SlackChannel channel = event.getChannel();
                //ignore own messages
                if (event.getSender().getUserName().equals(botName)) {
                    return;
                }
                if (state.equals(BotState.WAITING_TO_START)) {
                    //start the game when instructed
                    if (message.startsWith(botName)) {
                        if (message.contains("start")) {
                            startRegistration(channel);
                            sendPrompt("A game of The Resistance is starting. Type 'join' to join in. " +
                                    "Type 'done' when everyone who wants to play has joined.");
                        } else {
                            session.sendMessage(channel, "Say '" + botName + " start' to start a game of The Resistance.", null);
                        }
                    }

                } else if (state.equals(BotState.REGISTRATION)) {
                    if (message.contains("join")) {
                        if (!registerPlayer(event.getSender())) {
                            sendPublicMessageToPlayer(event.getSender().getUserName(),"You have already joined.");
                        } else {
                            sendPublicMessageToPlayer(event.getSender().getUserName()," is playing.");
                        }
                    } else if (message.contains("done")) {
                        //start the game, send out all the player roles
                        startGame();

                    }
                }

            }
        });

    }

    /**
     * starts the game using the usernames in playerUsernames. Let everyone know their roles. Let the
     * current leader know who they are
     */
    private void startGame() {
        sendPrompt("The game has begun. The players are " + GameMessageUtil.listPeople(playerUsernames));
        PreGameState preGameState = new PreGameState();
        pickTeamState = preGameState.startGame(Player.createFromUserNames(playerUsernames));

        Set<PlayerCharacter> spies = pickTeamState.getSituation().getSpies();
        for (PlayerCharacter gameCharacter : pickTeamState.getSituation().getPlayerCharacters()) {
            //tell the roles
            if (gameCharacter.isResistance()) {
                sendPrivateMessageToPlayer(gameCharacter.getUserName(),"You are a member of the resistance. If " +
                        " three missions succeed, you win! You're" +
                        " on the side of good. Hooray for you!");
            } else {
                sendPrivateMessageToPlayer(gameCharacter.getUserName(), "You are a spy along with " +
                        GameMessageUtil.listOtherPeople(spies, gameCharacter.getUserName()) + ".");
            }
        }

        //announce the leader
        sendPrompt("Attention " + pickTeamState.getSituation().getLeader().getUserName() + "! You are the current leader. " +
                "Pick " + pickTeamState.getSituation().getTeamSize() + " people to be on the team for the next mission." +
                "Use 'pick <username>' to add someone to the team. Use 'drop <username>' to remove them. " +
                "Or just say 'drop' to remove everyone. Can't decide? Use 'random' to pick a random team. Remember, you " +
                "can pick yourself as a team member.");
    }

    /**
     * Sends a direct message to the user.
     *
     * @param userName user to send the direct message to
     * @param message to send
     */
    private void sendPrivateMessageToPlayer(String userName, String message) {
        SlackUser user = session.findUserByUserName(userName);
        SlackMessageHandle<SlackChannelReply> openDirectHandle = session.openDirectMessageChannel(user);
        SlackChannel directChannel = openDirectHandle.getReply().getSlackChannel();
        session.sendMessage(directChannel,message,null);
    }

    /**
     * Requires that the gameChannel has already been set.
     * Sends a message in the game channel, @replying to the specified sender.
     *
     * @param recipient the person to @ reply
     * @param message the message to send them
     */
    private void sendPublicMessageToPlayer(String recipient, String message) {
        session.sendMessage(gameChannel, "@" + recipient + " " + message,null);
    }

    /**
     *
     * @param sender slack user to register in the game
     * @return true if the user isn't already registered using this method. false otherwise
     */
    private boolean registerPlayer(SlackUser sender) {
        if (playerUsernames.contains(sender.getUserName())) {
            return false;
        } else {
            playerUsernames.add(sender.getUserName());
            return true;
        }
    }

    /**
     * Switches to a state in which it will listen for player registration
     * @param gameChannel channel the game was started in which should be used for all public
     *                    communication.
     */
    private void startRegistration(SlackChannel gameChannel) {
        state = BotState.REGISTRATION;
        this.gameChannel = gameChannel;
    }

    /**
     * Sends the message to all players. Intended to be use for prompts that need to be repeated
     * if people aren't sure what to do. Tracks 'message' as the last message sent. Sends the
     * message out via the gameChannel (the channel that the game was started in)
     * Requires that the gameChannel has already been set.
     * @param message message to send.
     */
    private void sendPrompt(String message) {
        session.sendMessage(gameChannel,message,null);
        lastMessage = message;
    }
}