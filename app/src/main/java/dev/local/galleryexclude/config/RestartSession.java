package dev.local.galleryexclude.config;

/** Decisions are driven by process receipts, not by assuming a delay means restart succeeded. */
public final class RestartSession {
    public enum Action { PROBE, LAUNCH, STOP, SUCCEEDED, FAILED }
    public static final class Receipt {
        public final int pid,version;
        public final long revision;
        public Receipt(int pid,long revision,int version){this.pid=pid;this.revision=revision;this.version=version;}
    }
    private final long revision;
    private final int version;
    private int oldPid;
    private boolean launched,waitingForNew;
    public RestartSession(long revision,int version){this.revision=revision;this.version=version;}
    public int oldPid(){return oldPid;}
    public Action received(Receipt receipt){
        if(oldPid==0){
            if(receipt!=null){oldPid=receipt.pid;return Action.STOP;}
            if(!launched){launched=true;return Action.LAUNCH;}
            return Action.PROBE;
        }
        if(receipt==null){
            if(!waitingForNew){waitingForNew=true;return Action.LAUNCH;}
            return Action.PROBE;
        }
        if(receipt.pid==oldPid)return Action.PROBE;
        return receipt.revision==revision && receipt.version==version?Action.SUCCEEDED:Action.FAILED;
    }
}
