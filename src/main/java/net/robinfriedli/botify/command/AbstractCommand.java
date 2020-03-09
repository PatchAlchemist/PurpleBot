package net.robinfriedli.botify.command;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.commons.lang3.tuple.Pair;

import com.antkorwin.xsync.XMutexFactory;
import com.antkorwin.xsync.XMutexFactoryImpl;
import com.google.api.client.util.Sets;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.robinfriedli.botify.Botify;
import net.robinfriedli.botify.audio.spotify.SpotifyService;
import net.robinfriedli.botify.command.commands.general.AnswerCommand;
import net.robinfriedli.botify.command.interceptor.CommandInterceptorChain;
import net.robinfriedli.botify.command.interceptor.interceptors.CommandParserInterceptor;
import net.robinfriedli.botify.command.parser.CommandParser;
import net.robinfriedli.botify.concurrent.CommandExecutionTask;
import net.robinfriedli.botify.concurrent.ExecutionContext;
import net.robinfriedli.botify.concurrent.ThreadContext;
import net.robinfriedli.botify.discord.GuildManager;
import net.robinfriedli.botify.discord.MessageService;
import net.robinfriedli.botify.discord.property.properties.ColorSchemeProperty;
import net.robinfriedli.botify.entities.xml.CommandContribution;
import net.robinfriedli.botify.exceptions.Abort;
import net.robinfriedli.botify.exceptions.InvalidCommandException;
import net.robinfriedli.botify.function.CheckedRunnable;
import net.robinfriedli.botify.function.HibernateInvoker;
import net.robinfriedli.botify.function.SpotifyInvoker;
import net.robinfriedli.botify.login.Login;
import net.robinfriedli.botify.persist.StaticSessionProvider;
import net.robinfriedli.botify.persist.qb.QueryBuilderFactory;
import net.robinfriedli.botify.util.Util;
import net.robinfriedli.stringlist.StringList;
import net.robinfriedli.stringlist.StringListImpl;
import org.hibernate.Session;

/**
 * Abstract class to extend for any text based command implementation. Implementations need to be added and configured in the
 * commands.xml file, those will then be instantiated reflectively based on the configured command identifier and
 * the user input. This is handled by the {@link CommandManager} which then passes the initialized AbstractCommand
 * instance to the {@link CommandInterceptorChain} for execution. The used arguments and command input are parsed by the
 * {@link CommandParserInterceptor} by calling the {@link CommandParser}.
 */
public abstract class AbstractCommand implements Command {

    private static final XMutexFactory<Long> GUILD_MUTEX_FACTORY = new XMutexFactoryImpl<>();

    private final CommandContribution commandContribution;
    private final CommandContext context;
    private final CommandManager commandManager;
    private final ArgumentController argumentController;
    private final MessageService messageService;
    private final String commandBody;
    private final String identifier;
    private final String description;
    private final Category category;
    private boolean requiresInput;
    private String commandInput;
    // used to prevent onSuccess being called when no exception has been thrown but the command failed anyway
    private boolean isFailed;
    private CommandExecutionTask task;

    public AbstractCommand(CommandContribution commandContribution,
                           CommandContext context,
                           CommandManager commandManager,
                           String commandBody,
                           boolean requiresInput,
                           String identifier,
                           String description,
                           Category category) {
        this.commandContribution = commandContribution;
        this.context = context;
        this.commandManager = commandManager;
        this.requiresInput = requiresInput;
        this.commandBody = commandBody;
        this.identifier = identifier;
        this.description = description;
        this.category = category;
        this.argumentController = new ArgumentController(this);
        this.messageService = Botify.get().getMessageService();
        commandInput = "";
    }

    @Override
    public void onFailure() {
    }

    /**
     * Run logic with the given user choice after a {@link ClientQuestionEvent} has been completed. Called by
     * {@link AnswerCommand}.
     *
     * @param chosenOption the provided option chosen by the user
     */
    public void withUserResponse(Object chosenOption) throws Exception {
    }

    public void verify() {
        if (requiresInput() && getCommandInput().isBlank()) {
            throw new InvalidCommandException("That command requires more input!");
        }

        getArgumentController().verify();
    }

    /**
     * Clone this command instance to be executed with a new context and the current state of this command instance,
     * meaning this will copy the parsed command input and argument values. This is used by the {@link AnswerCommand}.
     *
     * @param newContext the new context
     * @return the freshly instantiated Command instance
     */
    public AbstractCommand fork(CommandContext newContext) {
        AbstractCommand newInstance = commandContribution.instantiate(commandManager, newContext, commandBody);
        newInstance.getArgumentController().transferValues(getArgumentController());
        newInstance.setCommandInput(commandInput);
        return newInstance;
    }

    /**
     * Run a custom command like you would enter it to discord, this includes commands and presets but excludes scripts
     * to avoid recursion.
     * This method is mainly used in groovy scripts.
     *
     * @param command the command string
     */
    public void run(String command) {
        StaticSessionProvider.consumeSession(session -> {
            CommandContext fork = context.fork(command, session);
            AbstractCommand abstractCommand = commandManager.instantiateCommandForContext(fork, session, false)
                .orElseThrow(() -> new InvalidCommandException("No command found for input"));
            ExecutionContext oldExecutionContext = ExecutionContext.Current.get();
            ExecutionContext.Current.set(fork);
            try {
                commandManager.getInterceptorChainWithoutScripting().intercept(abstractCommand);
            } finally {
                if (oldExecutionContext != null) {
                    ExecutionContext.Current.set(oldExecutionContext);
                } else {
                    ThreadContext.Current.drop(ExecutionContext.class);
                }
            }
        });
    }

    public ArgumentController getArgumentController() {
        return argumentController;
    }

    @Override
    public CommandContext getContext() {
        return context;
    }

    public MessageService getMessageService() {
        return messageService;
    }

    /**
     * @return whether or not the command is privileged, meaning it skips the ThreadExecutionQueue and gets executed
     * regardless of the queue being full
     */
    @Override
    public boolean isPrivileged() {
        return false;
    }

    @Override
    public CommandExecutionTask getTask() {
        return task;
    }

    @Override
    public void setTask(CommandExecutionTask task) {
        this.task = task;
    }

    protected void invoke(CheckedRunnable runnable) {
        invoke(() -> {
            runnable.doRun();
            return null;
        });
    }

    protected <E> E invoke(Callable<E> callable) {
        return createSynchronisedHibernateInvoker().invoke(callable);
    }

    protected void consumeSession(Consumer<Session> sessionConsumer) {
        invokeWithSession(session -> {
            sessionConsumer.accept(session);
            return null;
        });
    }

    protected <E> E invokeWithSession(Function<Session, E> function) {
        return createSynchronisedHibernateInvoker().invoke(function);
    }

    /**
     * @return a {@link HibernateInvoker} that uses the current guild object as synchronisation lock to make sure the
     * same guild cannot invoke the task concurrently. This is to counteract the spamming of a command that uses this
     * method, e.g. spamming the add command concurrently could evade the playlist size limit.
     */
    protected HibernateInvoker createSynchronisedHibernateInvoker() {
        return HibernateInvoker.create(getContext().getSession(), GUILD_MUTEX_FACTORY.getMutex(context.getGuild().getIdLong()));
    }

    /**
     * Run code after setting the spotify access token to the one of the current user's login. This is required for
     * commands that do not necessarily require a login, in which case the access token will always be set before
     * executing the command, but might need to access a user's data under some condition.
     *
     * @param callable the code to run
     */
    protected <E> E runWithLogin(Callable<E> callable) throws Exception {
        Login login = Botify.get().getLoginManager().requireLoginForUser(getContext().getUser());
        return SpotifyInvoker.create(getContext().getSpotifyApi(), login).invoke(callable);
    }

    /**
     * Run a callable with the default spotify credentials. Used for spotify api queries in commands.
     */
    protected <E> E runWithCredentials(Callable<E> callable) throws Exception {
        return SpotifyInvoker.create(getContext().getSpotifyApi()).invoke(callable);
    }

    protected boolean argumentSet(String argument) {
        return argumentController.argumentSet(argument);
    }

    protected String getArgumentValue(String argument) {
        return getArgumentValue(argument, String.class);
    }

    protected <E> E getArgumentValue(String argument, Class<E> type) {
        return getArgumentValue(argument, type, null);
    }

    protected <E> E getArgumentValue(String argument, Class<E> type, E alternativeValue) {
        ArgumentController.ArgumentUsage usedArgument = argumentController.getUsedArgument(argument);
        if (usedArgument == null) {
            if (alternativeValue != null) {
                return alternativeValue;
            }
            throw new InvalidCommandException("Expected argument: " + argument);
        }

        if (!usedArgument.hasValue()) {
            if (alternativeValue != null) {
                return alternativeValue;
            } else {
                throw new InvalidCommandException("Argument '" + argument + "' requires an assigned value!");
            }
        }

        return usedArgument.getValue(type);
    }

    protected boolean isPartitioned() {
        return Botify.get().getGuildManager().getMode() == GuildManager.Mode.PARTITIONED;
    }

    /**
     * askQuestion implementation that uses the index of the option as its key and accepts a function to get the display
     * for each option
     *
     * @param options     the available options
     * @param displayFunc the function that returns the display for each option (e.g. for a Track it should be a function
     *                    that returns the track's name and artists)
     * @param <O>         the type of options
     */
    public <O> void askQuestion(List<O> options, Function<O, String> displayFunc) {
        ClientQuestionEvent question = new ClientQuestionEvent(this);
        for (int i = 0; i < options.size(); i++) {
            O option = options.get(i);
            question.mapOption(String.valueOf(i), option, displayFunc.apply(option));
        }

        question.mapOption("all", options, "All of the above");
        askQuestion(question);
    }

    /**
     * like {@link #askQuestion(List, Function)} but adds the result of the given detailFunc to each option if several
     * options have the same display
     */
    protected <O> void askQuestion(List<O> options, Function<O, String> displayFunc, Function<O, String> detailFunc) {
        Map<O, String> optionWithDisplay = new LinkedHashMap<>();
        Set<O> optionsWithDuplicateDisplay = Sets.newHashSet();
        for (O option : options) {
            String display = displayFunc.apply(option);
            if (optionWithDisplay.containsValue(display)) {
                optionsWithDuplicateDisplay.addAll(Util.getKeysForValue(optionWithDisplay, display));
                optionsWithDuplicateDisplay.add(option);
            }

            // naively put the display in the map no matter if it already exists. This will retain order in the LinkedMap
            // and make sure the detail part does not get added twice if two options have the same detail display
            optionWithDisplay.put(option, display);
        }

        for (O o : optionsWithDuplicateDisplay) {
            String display = optionWithDisplay.get(o);
            optionWithDisplay.put(o, display + " (" + detailFunc.apply(o) + ")");
        }

        askQuestion(options, optionWithDisplay::get);
    }

    protected void askQuestion(ClientQuestionEvent question) {
        setFailed(true);
        question.ask();
    }

    protected CompletableFuture<Message> sendMessage(String message) {
        return messageService.send(message, getContext().getChannel());
    }

    protected CompletableFuture<Message> sendMessage(EmbedBuilder message) {
        message.setColor(ColorSchemeProperty.getColor());
        return messageService.send(message.build(), getContext().getChannel());
    }

    protected CompletableFuture<Message> sendMessage(MessageEmbed messageEmbed) {
        return messageService.send(messageEmbed, getContext().getChannel());
    }

    protected CompletableFuture<Message> sendMessage(User user, String message) {
        return messageService.send(message, user);
    }

    protected void sendWrapped(String message, String wrapper, MessageChannel channel) {
        messageService.sendWrapped(message, wrapper, channel);
    }

    protected CompletableFuture<Message> sendMessage(InputStream file, String fileName, MessageBuilder messageBuilder) {
        return messageService.send(messageBuilder, file, fileName, getContext().getChannel());
    }

    protected CompletableFuture<Message> sendWithLogo(EmbedBuilder embedBuilder) {
        return messageService.sendWithLogo(embedBuilder, getContext().getChannel());
    }

    protected CompletableFuture<Message> sendSuccess(String message) {
        return messageService.sendSuccess(message, getContext().getChannel());
    }

    protected CompletableFuture<Message> sendError(String message) {
        return messageService.sendError(message, getContext().getChannel());
    }

    protected void sendToActiveGuilds(MessageEmbed message) {
        messageService.sendToActiveGuilds(message, getContext().getSession());
    }

    /**
     * Used for any command with an A $to B syntax.
     *
     * @return both halves of the logical two sided statement
     * @deprecated replaced by the {@link CommandParser}; inline arguments are now treated as regular arguments with their
     * right side up to the next argument as value. This has the benefit the the order and location of inline arguments
     * no longer matters. Meaning 'insert a $to b $at c' could also be written 'insert a $at c $to b' or even
     * 'insert $to=b $at=c a'
     */
    @Deprecated
    protected Pair<String, String> splitInlineArgument(String argument) {
        return splitInlineArgument(getCommandInput(), argument);
    }

    @Deprecated
    protected Pair<String, String> splitInlineArgument(String part, String argument) {
        StringList words = StringListImpl.createWithRegex(part, " ");

        List<Integer> positions = words.findPositionsOf("$" + argument, true);
        if (positions.isEmpty()) {
            throw new InvalidCommandException("Expected inline argument: " + argument);
        }
        int position = positions.get(0);

        if (position == 0 || position == words.size() - 1) {
            throw new InvalidCommandException("No input before or after inline argument: " + argument);
        }

        String left = words.subList(0, position).toSeparatedString(" ");
        String right = words.subList(position + 1, words.size()).toSeparatedString(" ");

        if (left.isBlank() || right.isBlank()) {
            throw new InvalidCommandException("No input before or after inline argument: " + argument);
        }

        return Pair.of(left.trim(), right.trim());
    }

    /**
     * @return the string following the command identifier. This represents the command string used to parse the
     * arguments and command input
     */
    @Override
    public String getCommandBody() {
        return commandBody;
    }

    /**
     * @return the String this command gets referenced with
     */
    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public boolean isFailed() {
        return isFailed;
    }

    public void setFailed(boolean isFailed) {
        this.isFailed = isFailed;
    }

    public void abort() {
        throw new Abort();
    }

    @Override
    public String display() {
        String contentDisplay = context.getMessage().getContentDisplay();
        return contentDisplay.length() > 150 ? contentDisplay.substring(0, 150) + "[...]" : contentDisplay;
    }

    public Category getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public CommandManager getManager() {
        return commandManager;
    }

    public String getCommandInput() {
        return commandInput;
    }

    public void setCommandInput(String commandInput) {
        this.commandInput = commandInput;
    }

    public boolean requiresInput() {
        return requiresInput;
    }

    public CommandContribution getCommandContribution() {
        return commandContribution;
    }

    public SpotifyService getSpotifyService() {
        return context.getSpotifyService();
    }

    public QueryBuilderFactory getQueryBuilderFactory() {
        return Botify.get().getQueryBuilderFactory();
    }

    /**
     * @param commandString the string following the command identifier
     * @deprecated replaced by the {@link CommandParser}
     */
    @Deprecated
    private void processCommand(String commandString) {
        StringList words = StringListImpl.separateString(commandString, " ");

        int commandBodyIndex = 0;
        for (String word : words) {
            if (word.startsWith("$")) {
                String argString = word.replaceFirst("\\$", "");
                // check if the argument has an assigned value
                int equalsIndex = argString.indexOf("=");
                if (equalsIndex > -1) {
                    if (equalsIndex == 0 || equalsIndex == word.length() - 1) {
                        throw new InvalidCommandException("Malformed argument. Equals sign cannot be first or last character.");
                    }
                    String argument = argString.substring(0, equalsIndex);
                    String value = argString.substring(equalsIndex + 1);
                    argumentController.setArgument(argument.toLowerCase(), value);
                } else {
                    argumentController.setArgument(argString.toLowerCase());
                }
            } else if (!word.isBlank()) {
                break;
            }
            commandBodyIndex += word.length();
        }

        commandInput = words.toString().substring(commandBodyIndex).trim();
    }

    public enum Category {

        PLAYBACK("playback", "Commands that manage the music playback"),
        PLAYLIST_MANAGEMENT("playlist management", "Commands that add or remove items from botify playlists"),
        GENERAL("general", "General commands that manage this bot"),
        CUSTOMISATION("customisation", "Commands to customise the bot"),
        SPOTIFY("spotify", "Commands that manage the Spotify login or upload playlists to Spotify"),
        SCRIPTING("scripting", "Commands that execute or manage groovy scripts"),
        SEARCH("search", "Commands that search for botify playlists or list all of them or search for Spotify and Youtube tracks, videos and playlists"),
        WEB("web", "Commands that manage the web client"),
        ADMIN("admin", "Commands only available to administrators defined in settings-private.properties");

        private final String name;
        private final String description;

        Category(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }
    }

}
