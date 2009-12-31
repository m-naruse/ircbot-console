package com.enjoyxstudy.ircbotconsole;

import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import org.jibble.pircbot.IrcException;
import org.jibble.pircbot.NickAlreadyInUseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enjoyxstudy.ircbotconsole.command.LogWriteProcessor;
import com.enjoyxstudy.ircbotconsole.command.RecieveCommandHandler;
import com.enjoyxstudy.ircbotconsole.notifier.Notifier;
import com.enjoyxstudy.ircbotconsole.notifier.NotifierThread;
import com.enjoyxstudy.ircbotconsole.notifier.RssNotifierCreator;

/**
 * IRCボットのサーバです。
 * 本クラスでIRCボットの操作から、設定内容の保持など、すべての処理を管理します。
 *
 * @author onozaty
 */
public class IrcBotServer {

    /** loggerです。 */
    static Logger logger = LoggerFactory.getLogger(NotifierThread.class);

    /** 設定内容を保存するファイル名です。 */
    private static final String CONFIG_SAVE_FILE_NAME = "config.xml";

    /** RSS通知の作業ディレクトリ名です。 */
    private static final String RSSNOTIFER_DIR_NAME = "rss";

    /** IrcBotServerのインスタンスです。(1つだけしか生成しない) */
    private static IrcBotServer instance = new IrcBotServer();

    /** 再接続チェック処理でWAITする時間(ミリ秒)です。 */
    protected static final int RECONNECT_WAIT_TIMMING = 20 * 1000;

    /** 設定内容です。 */
    private Config config;

    /** IRCボットです。 */
    IrcBot ircBot;

    /** 通知処理を行うスレッドです。 */
    private NotifierThread notifierThread;

    /** 再接続を行うスレッドです。 */
    private Thread reconnectThread;

    /**
     * 基準となるディレクトリです。
     * この配下にログや作業用ディレクトリが作成されます。
     */
    private File homeDirectory;

    /** 設定内容を保存するファイルです。 */
    private File configSaveFile;

    /** 停止処理中を表すフラグです。 */
    boolean isDestroy = false;

    /**
     * コンストラクタです。
     *
     */
    private IrcBotServer() {
        super();
    }

    /**
     * 初期化を行います。
     *
     * @param homeDirectoryPath 基準となるディレクトリ
     * @throws NickAlreadyInUseException
     * @throws IOException
     * @throws IrcException
     * @throws NoSuchAlgorithmException
     * @throws InterruptedException
     */
    public synchronized void init(String homeDirectoryPath)
            throws NickAlreadyInUseException, IOException, IrcException,
            NoSuchAlgorithmException, InterruptedException {

        if (ircBot != null) {
            throw new IllegalStateException("すでに起動済みです。");
        }

        logger.info("起動処理を開始します。 homeディレクトリ=[{}]", homeDirectoryPath);

        this.homeDirectory = new File(homeDirectoryPath);

        if (!this.homeDirectory.exists()) {
            // 存在しない場合作成
            if (this.homeDirectory.mkdir()) {
                throw new IOException("ディレクトリの作成に失敗しました。 "
                        + this.homeDirectory.getAbsolutePath());
            }
        }

        // 設定内容の読み込み
        configSaveFile = new File(homeDirectoryPath, CONFIG_SAVE_FILE_NAME);
        loadConfig();

        // IRCボット生成
        ircBot = new IrcBot(new RecieveCommandHandler());

        // 接続情報を設定
        applyConnectInfo();

        // ログ出力を設定
        ircBot.getRecieveCommandHandler().addProcessor(
                null,
                new LogWriteProcessor(new File(homeDirectory, config
                        .getLogDirectory()).getAbsolutePath()));

        // IRCに接続
        try {
            connectIrc();
        } catch (Exception e) {
            logger.error("接続に失敗しました。", e);
            // そのまま周期処理の起動を実施
        }

        // 通知処理を開始
        startNotifierThread();

        // 通知処理を開始
        startReconnectThread();

        logger.info("起動処理が完了しました。");
    }

    /**
     * 停止します。
     *
     * @throws FileNotFoundException
     */
    public synchronized void destroy() throws FileNotFoundException {

        if (ircBot == null) {
            throw new IllegalStateException("すでに停止済みです。");
        }

        logger.info("停止処理を開始します。");

        // 設定内容を保存
        saveConfig();

        disconnect();
        try {
            ircBot.dispose();
        } catch (Exception e) {
            logger.warn("IRC接続の破棄でエラーが発生しました。", e);
        }
        ircBot = null;

        isDestroy = true;

        try {
            if (notifierThread != null) {
                notifierThread.setStop(true);
                notifierThread.join();
                notifierThread = null;
            }

            if (reconnectThread != null) {
                reconnectThread.join();
                reconnectThread = null;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        logger.info("停止処理が完了しました。");
    }

    /**
     * 接続情報を更新し、再接続を行います。
     *
     * @param serverName
     * @param serverPort
     * @param serverPassword
     * @param nick
     * @param encoding
     *
     * @throws IrcException
     * @throws IOException
     * @throws NoSuchAlgorithmException
     * @throws NickAlreadyInUseException
     */
    public synchronized void updateConnetInfo(String serverName,
            int serverPort, String serverPassword, String nick, String encoding)
            throws NickAlreadyInUseException, NoSuchAlgorithmException,
            IOException, IrcException {

        logger.info("接続情報を更新します。");

        config.setServerName(serverName);
        config.setServerPort(serverPort);
        config.setServerPassword(serverPassword);
        config.setNick(nick);
        config.setEncoding(encoding);

        if (ircBot.isConnected()) {
            disconnect();
        }

        // 接続情報を反映
        applyConnectInfo();

        // 接続
        connectIrc();

        // 設定情報をいったん保存
        saveConfig();
        logger.info("接続情報の更新が完了しました。");
    }

    /**
     * RssNotifierを追加します。
     *
     * @param target チャンネル名
     * @param feedUrl RSSフィード
     * @throws InterruptedException
     * @throws NoSuchAlgorithmException
     * @throws FileNotFoundException
     */
    public synchronized void addRssNotifer(String target, String feedUrl)
            throws NoSuchAlgorithmException, InterruptedException,
            FileNotFoundException {

        logger.info("RSS通知を追加します。 チャンネル名=[{}] フィード=[{}]", target, feedUrl);

        ArrayList<String> feeds = config.getRssNotiferConfig().get(target);
        if (feeds == null) {
            // チャンネルで初回の場合はリストを作成
            feeds = new ArrayList<String>();
            config.getRssNotiferConfig().put(target, feeds);
        }

        // 該当のフィードを追加し、通知スレッドを再起動
        feeds.add(feedUrl);
        restartNotifierThread();

        saveConfig();
        logger.info("RSS通知の追加が完了しました。");
    }

    /**
     * RssNotifierを削除します。
     *
     * @param target チャンネル名
     * @param feedUrl RSSフィード
     * @throws InterruptedException
     * @throws NoSuchAlgorithmException
     * @throws FileNotFoundException
     */
    public synchronized void removeRssNotifer(String target, String feedUrl)
            throws NoSuchAlgorithmException, InterruptedException,
            FileNotFoundException {

        logger.info("RSS通知を削除します。 チャンネル名=[{}] フィード=[{}]", target, feedUrl);

        ArrayList<String> feeds = config.getRssNotiferConfig().get(target);
        if (feeds != null) {
            // 該当のフィードを削除し、通知スレッドを再起動
            feeds.remove(feedUrl);
            restartNotifierThread();
        }

        saveConfig();
        logger.info("RSS通知の削除が完了しました。");
    }

    /**
     * 接続状況をチェックし、未接続の場合、再接続を行います。
     */
    public synchronized void checkReconnect() {

        if (!isConnected() && config.getServerName() != null
                && !config.getServerName().equals("")) {

            logger.debug("再接続処理を開始します。");
            try {
                ircBot.connectJoin();
                logger.info("再接続に成功しました。 サーバ=[{}:{}]", config.getServerName(),
                        new Integer(config.getServerPort()));
            } catch (Exception e) {
                logger.debug("再接続に失敗しました。", e);
            }
        }
    }

    /**
     * チャンネルを追加します。
     *
     * @param channel
     * @throws FileNotFoundException
     */
    public void addChannel(String channel) throws FileNotFoundException {

        logger.info("チャンネルを追加します。 チャンネル名=[{}]", channel);
        config.getChannels().add(channel);

        if (ircBot.isConnected()) {
            ircBot.joinChannel(channel);
        }

        saveConfig();
        logger.info("チャンネルの追加が完了しました。");
    }

    /**
     * チャンネルを削除します。
     *
     * @param channel
     * @throws InterruptedException
     * @throws NoSuchAlgorithmException
     * @throws FileNotFoundException
     */
    public void removeChannel(String channel) throws NoSuchAlgorithmException,
            InterruptedException, FileNotFoundException {

        logger.info("チャンネルを削除します。 チャンネル名=[{}]", channel);
        config.getChannels().remove(channel);

        if (ircBot.isConnected()) {
            ircBot.partChannel(channel);
        }

        ArrayList<String> feeds = config.getRssNotiferConfig().remove(channel);
        if (feeds != null) {
            // 該当のフィードを削除し、通知スレッドを再起動
            restartNotifierThread();
        }

        saveConfig();
        logger.info("チャンネルの削除が完了しました。");
    }

    /**
     * IRCに接続します。
     *
     * @throws NickAlreadyInUseException
     * @throws IOException
     * @throws IrcException
     * @throws NoSuchAlgorithmException
     */
    private void connectIrc() throws NickAlreadyInUseException, IOException,
            IrcException, NoSuchAlgorithmException {

        if (config.getServerName() != null
                && !config.getServerName().equals("")) {

            logger.info("接続処理を開始します。 サーバ=[{}:{}]", config.getServerName(),
                    new Integer(config.getServerPort()));
            ircBot.connectJoin();
            logger.info("接続処理が完了しました。");

        } else {
            logger.info("IRCサーバの設定がないため、IRCサーバへの接続処理をスキップします。");
        }
    }

    /**
     * 切断処理を行います。
     */
    private void disconnect() {

        logger.info("切断処理を開始します。");
        ircBot.disconnect();

        // 切断要求後、すぐに切断となるわけではないため
        // 最大10秒(100ms×100)待ち合わせ
        for (int i = 0; i < 100; i++) {
            if (!ircBot.isConnected()) {
                logger.info("切断処理が完了しました。");
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * IRCサーバへの接続情報をIRCボットに設定します。
     *
     * @throws UnsupportedEncodingException
     */
    private void applyConnectInfo() throws UnsupportedEncodingException {

        // 各種情報を設定
        ircBot.setNickName(config.getNick());
        ircBot.setServer(config.getServerName());
        ircBot.setPort(config.getServerPort());
        ircBot.setPassword(config.getServerPassword());
        ircBot.setEncoding(config.getEncoding());
        ircBot.setAutoJoinChannels(config.getChannels());
    }

    /**
     * 通知処理を行うスレッドを開始します。
     *
     * @throws NoSuchAlgorithmException
     * @throws InterruptedException
     */
    private void startNotifierThread() throws NoSuchAlgorithmException,
            InterruptedException {

        File workDirectory = new File(homeDirectory, config.getWorkDirectory());
        if (!workDirectory.exists()) {
            // 作業用ディレクトリが存在しない場合は作成
            workDirectory.mkdir();
        }

        File rssNotiferWorkDirectory = new File(workDirectory,
                RSSNOTIFER_DIR_NAME);
        if (!rssNotiferWorkDirectory.exists()) {
            // 作業用ディレクトリが存在しない場合は作成
            rssNotiferWorkDirectory.mkdir();
        }

        RssNotifierCreator rssNotifierCreator = new RssNotifierCreator(
                rssNotiferWorkDirectory);
        List<Notifier> rssNotifiers = rssNotifierCreator.createNotifiers(config
                .getRssNotiferConfig());

        notifierThread = new NotifierThread(ircBot, rssNotifiers);
        notifierThread.start();
    }

    /**
     * 通知処理を行うスレッドを再起動します。
     *
     * @throws NoSuchAlgorithmException
     * @throws InterruptedException
     */
    private void restartNotifierThread() throws NoSuchAlgorithmException,
            InterruptedException {

        // いったん停止
        if (notifierThread != null) {
            notifierThread.setStop(true);
            notifierThread.join();
            notifierThread = null;
        }

        // 開始
        startNotifierThread();
    }

    /**
     * IRCの再接続処理を行うスレッドを開始します。
     */
    private void startReconnectThread() {

        reconnectThread = new Thread(new Runnable() {

            public void run() {
                logger.info("再接続チェックスレッドを開始します。");
                while (!isDestroy) {
                    checkReconnect();

                    try {
                        Thread.sleep(RECONNECT_WAIT_TIMMING);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                logger.info("再接続チェックスレッドを終了します。");
            }
        });
        reconnectThread.start();
    }

    /**
     * 設定内容を読み込みます。
     *
     * @throws FileNotFoundException
     */
    private void loadConfig() throws FileNotFoundException {

        if (!configSaveFile.exists()) {
            config = new Config();
        } else {
            XMLDecoder decoder = new XMLDecoder(new BufferedInputStream(
                    new FileInputStream(configSaveFile)));
            try {
                config = (Config) decoder.readObject();
                if (config == null) {
                    logger.warn("設定内容の読み込みに失敗しました。");
                    config = new Config();
                } else {
                    logger.info("設定内容を読み込みました。");
                }
            } finally {
                decoder.close();
            }
        }
    }

    /**
     * 設定内容を保存します。
     *
     * @throws FileNotFoundException
     */
    private void saveConfig() throws FileNotFoundException {

        XMLEncoder encoder = new XMLEncoder(new BufferedOutputStream(
                new FileOutputStream(configSaveFile)));
        try {
            encoder.writeObject(config);
            logger.info("設定内容を保存しました。");
        } finally {
            encoder.close();
        }
    }

    /**
     * 接続中かどうかを返却します。
     *
     * @return 接続中の場合true
     */
    public boolean isConnected() {
        return ircBot.isConnected();
    }

    /**
     * インスタンスを取得します。
     * @return IrcBotServerのインスタンス
     */
    public static IrcBotServer getInstance() {
        return instance;
    }

    /**
     * Configを返却します。
     *
     * @return config
     */
    public Config getConfig() {
        return config;
    }

    /**
     * 基準となるディレクトリを返却します。
     *
     * @return homeDirectory
     */
    public File getHomeDirectory() {
        return homeDirectory;
    }

}
