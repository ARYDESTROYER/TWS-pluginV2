package theWorst.votes;

import arc.Events;
import arc.math.Mathf;
import arc.util.Time;
import mindustry.entities.type.Player;
import mindustry.game.EventType;
import theWorst.Global;
import theWorst.Main;
import theWorst.database.*;
import theWorst.helpers.Administration;
import theWorst.helpers.Destroyable;
import theWorst.helpers.Displayable;
import theWorst.helpers.Hud;
import theWorst.helpers.gameChangers.ItemStack;
import theWorst.helpers.gameChangers.UnitStack;


import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static mindustry.Vars.*;
import static theWorst.Tools.Formatting.*;
import static theWorst.Tools.General.getRank;
import static theWorst.Tools.Json.loadSimpleHashmap;
import static theWorst.Tools.Json.saveSimple;
import static theWorst.Tools.Players.getTranslation;
import static theWorst.Tools.Players.sendErrMessage;

public class Vote implements Displayable, Destroyable {
    static String passiveFile = Global.saveDir + "passive.json";
    static HashMap<String, Integer> passivePlayers = new HashMap<>();
    public VoteData voteData;
    String message;
    String[] args;
    final String mode;

    Set<String> voted = new HashSet<>();
    Administration.RecentMap recent = new Administration.RecentMap("vote-can-egan"){
        @Override
        public long getPenalty() {
            return 60 * 1000;
        }
    };

    boolean canVote = true;
    public boolean voting = false;
    boolean special = false;

    Integer maxReq = null;

    final int voteDuration = 60;
    int no, yes;
    int time;

    public Vote(String mode){
        this.mode = mode;
        Hud.addDisplayable(this);
        Main.addDestroyable(this);
        Events.on(EventType.GameOverEvent.class, e->canVote = false);
        Events.on(EventType.PlayEvent.class, e->canVote = true);

    }

    public void aVote(VoteData voteData, Integer maxReq, SpecialRank sr, String ... args) {
        Player player = voteData.by;
        Database.getData(player).onAction(player);
        if (!canVote) {
            sendErrMessage(player, "vote-cannot-vote");
            return;
        }
        if (voting) {
            sendErrMessage(player, "vote-in-process");
            return;
        }
        if (getRank(Database.getData(player)).equals(Rank.griefer)) {
            sendErrMessage(player, "griefer-no-perm");
            return;
        }
        Long pen = recent.contains(player);
        if (pen != null && pen > 0) {
            sendErrMessage(player, "vote-is-recent", milsToTime(pen));
            return;
        }
        this.voteData = voteData;
        this.message = voteData.reason;
        this.maxReq = maxReq;
        this.args = args;
        voted.clear();
        yes = 0;
        no = 0;
        time = voteDuration;
        voting = true;
        special = false;
        if(sr != null && voteData.special != null && sr.permissions.contains(voteData.special.name())) {
            time = voteDuration/2;
            special = true;
            Hud.addAd("vote-special", 10, sr.getSuffix(), "!white", "!gray");
        }
        passivePlayers.remove(player.uuid);
        addVote(player, "y");
        notifyPlayers();
    }

    private void notifyPlayers() {
        for(Player p : playerGroup){
            if(Database.hasEnabled(p, Setting.hud)) continue;
            player.sendMessage(getMessage(Database.getData(p)));
        }
    }


    public int getRequired() {
        int count = 0;
        for (Player p : playerGroup) {
            PlayerD pd = Database.getData(p);
            if (pd.rank.equals(Rank.griefer.name()) || pd.afk) continue;
            if (pd.playTime + Time.timeSinceMillis(pd.connected) < Global.limits.minVotePlayTime) continue;
            if (passivePlayers.getOrDefault(p.uuid, 0) > 1) continue;
            count += 1;
        }
        if (count == 2) {
            return 2;
        }
        int res = (int) Math.ceil(count / 2.0);
        if (maxReq != null) res = Mathf.clamp(res, 1, maxReq);
        return res;
    }

    public void addVote(Player player, String vote) {
        if(!voting){
            sendErrMessage(player, "vote-not-active");
        }
        PlayerD pd=Database.getData(player);
        pd.onAction(player);
        long totalPT = pd.playTime + Time.timeSinceMillis(pd.connected);
        if (voted.contains(player.uuid)) {
            sendErrMessage( player, "vote-already-voted");
            return;
        }
        if(totalPT < Global.limits.minVotePlayTime){
            sendErrMessage(player, "vote-low-play-time", milsToTime(totalPT));
            return;
        }
        if(pd.rank.equals(Rank.griefer.name())){
            sendErrMessage(player, "griefer-no-perm");
            return;
        }
        voted.add(player.uuid);
        if (vote.equals("y")) yes += 1;
        else no += 1;
        resolve();
        notifyPlayers();
        passivePlayers.remove(player.uuid);
    }

    public void resolve(){
        if(!voting) return;
        int req = getRequired();
        if (no >= req) close(false);
        else if (yes >= req) close(true);
    }

    public void close(boolean success) {
        if (!voting) return;
        voting = false;
        if (success) {
            voteData.run();
            Hud.addAd(voteData.reason + "-done", 10, args);
            PlayerD pd = Database.getData(voteData.by);
            if(voteData.special == Perm.loadout){
                pd.loadoutVotes++;
            } else if(voteData.special == Perm.factory){
                pd.factoryVotes++;
            }
        } else {
            if(!Database.hasPerm(voteData.by, Perm.high)){
                recent.add(voteData.by);
                sendErrMessage(voteData.by, "vote-failed-penalty");
            }
            Hud.addAd(voteData.reason + "-fail", 10, args);
        }
        for(Player p : playerGroup) {
            if(voted.contains(p.uuid)) continue;
            passivePlayers.put(p.uuid, passivePlayers.getOrDefault(p.uuid, 0) + 1);
        }
        saveSimple(passiveFile, passivePlayers, null);
    }

    @Override
    public String getMessage(PlayerD pd) {
        if(!voting) return null;
        String color = time % 2 == 0 ? "gray" : "white";
        String md = getTranslation(pd,mode);
        String fMessage = format(getTranslation(pd,message),args);
        String req = getTranslation(pd,"vote-req");
        return String.format("[%s]%s %s %02ds [green]%d[] : [scarlet]%d[] [gray]%s %d[][]",
                color,fMessage,md,time,yes,no,req,getRequired());
    }

    @Override
    public void onTick() {
        if(!voting) return;
        time--;
        if(time <= 0){
            if(special){
                close(yes > no);
            } else {
                close(false);
            }
        }
    }

    @Override
    public void destroy() {
        close(false);
    }

    public static void loadPassive(){
        passivePlayers = loadSimpleHashmap(passiveFile, Integer.class, ()->{});
        if(passivePlayers == null) passivePlayers = new HashMap<>();
    }
}
