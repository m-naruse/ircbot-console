package com.enjoyxstudy.ircbotconsole.notifier;

import com.enjoyxstudy.ircbotconsole.IrcBot;

/**
 * 定期的にIRCに通知するクラスの抽象クラスです。
 *
 * @author onozaty
 */
public abstract class AbstractNotifier implements Notifier {

    /** 実行中を表すフラグです。 */
    protected boolean isRunning;

    /** 通知先チャンネルです。 */
    protected String channel;

    /** 次の通知日時を管理するクラスです。 */
    protected Scheduler scheduler;

    /**
     * コンストラクタです。
     *
     * @param channel
     * @param scheduler
     */
    public AbstractNotifier(String channel, Scheduler scheduler) {
        super();
        this.channel = channel;
        this.scheduler = scheduler;
    }

    /**
     * @throws Exception
     * @see com.enjoyxstudy.ircbotconsole.notifier.Notifier#execute(com.enjoyxstudy.ircbotconsole.IrcBot)
     */
    public void execute(IrcBot ircBot) throws Exception {

        isRunning = true;
        try {
            String[] messages = createMessage(ircBot);
            if (messages != null) {
                notifyMessage(ircBot, messages);
            }
        } finally {
            scheduler.nextSchedule();
            isRunning = false;
        }
    }

    /**
     * メッセージを通知します。
     *
     * @param ircBot
     * @param messages
     */
    protected void notifyMessage(IrcBot ircBot, String[] messages) {
        ircBot.sendMessages(channel, messages);
    }

    /**
     * @param ircBot
     * @return 通知メッセージ
     * @throws Exception
     */
    protected abstract String[] createMessage(IrcBot ircBot) throws Exception;

    /**
     * @see com.enjoyxstudy.ircbotconsole.notifier.Notifier#isExecutable()
     */
    public boolean isExecutable() {

        if (isRunning) {
            return false;
        }

        return scheduler.isExecutable();
    }

}
