package theWorst.helpers;

import arc.Events;
import arc.math.Mathf;
import arc.util.Time;
import arc.util.Timer;
import mindustry.entities.type.Player;
import mindustry.game.EventType;
import mindustry.world.StaticTree;
import mindustry.world.Tile;
import theWorst.Bot;
import theWorst.Global;
import theWorst.database.*;

import java.util.ArrayList;
import java.util.HashMap;

import static mindustry.Vars.*;
import static theWorst.Tools.Formatting.*;
import static theWorst.Tools.General.getRank;
import static theWorst.Tools.Players.*;

public class Administration implements Displayable{

    public static Emergency emergency = new Emergency(0); // Its just placeholder because time is 0
    public static HashMap<String, ArrayList<Long>> recentWithdraws = new HashMap<>();
    public static RecentMap banned = new RecentMap("ag-can-withdraw-egan"){
        @Override
        public long getPenalty() {
            return Global.limits.withdrawPenalty;
        }
    };
    public static RecentMap doubleClicks = new RecentMap(null) {
        @Override
        public long getPenalty() {
            return 300;
        }
    };
    public static Timer.Task recentThread;
    TileInfo[][] data;
    public static HashMap<String, ArrayList<Action>> undo = new HashMap<>();
    final int undoCache = 200;

    public Administration() {
        Action.register();
        Hud.addDisplayable(this);
        //this updates recent map of deposit and withdraw events.
        if(recentThread != null) recentThread.cancel();

        //crete a a new action map when map changes.
        Events.on(EventType.PlayEvent.class, e -> {
            data = new TileInfo[world.height()][world.width()];
            for (int y = 0; y < world.height(); y++) {
                for (int x = 0; x < world.width(); x++) {
                    data[y][x] = new TileInfo();
                }
            }
            undo.clear();
            Action.buildBreaks.clear();
        });

        //displaying of inspect messages
        Events.on(EventType.TapConfigEvent.class, e-> {
            if(e.player == null) return;
            handleInspect(e.player, e.tile);
        });
        Events.on(EventType.TapEvent.class, e ->{
            if(e.player == null) return;
            handleInspect(e.player, e.tile);
        } );

        //disable lock if block wos destroyed
        Events.on(EventType.BlockDestroyEvent.class, e -> data[e.tile.y][e.tile.x].lock = 0);
        //this is against spam bots
        Events.on(EventType.PlayerChatEvent.class, e -> {
            if (!e.message.startsWith("/")) return;
            if (CommandUser.map.containsKey(e.player.uuid)) {
                CommandUser.map.get(e.player.uuid).addOne(e.player);
                return;
            }
            CommandUser.map.put(e.player.uuid, new CommandUser(e.player));
        });
        //adding filters, it has to be done after server is loaded
        Events.on(EventType.ServerLoadEvent.class, e -> {
            netServer.admins.addChatFilter((player, message) -> {
                //do not display how people voted
                if (message.equals("y") || message.equals("n")) return null;
                PlayerD pd = Database.getData(player);
                String color = pd.textColor;
                if (!Database.hasEnabled(player, Setting.chat)) {
                    sendErrMessage(player, "chat-disabled");
                    return null;
                }
                //handle griefer messages
                if (pd.rank.equals(Rank.griefer.name())) {
                    if (Time.timeSinceMillis(pd.lastMessage) < Global.limits.grieferAntiSpamTime) {
                        sendErrMessage(player, "griefer-too-match-messages");
                        return null;
                    }
                    color = "pink";

                }
                //handle users with color combo permission
                String[] colors = pd.textColor.split("/");
                if (Database.hasSpecialPerm(player, Perm.colorCombo) && colors.length > 1) {
                    message = smoothColors(message,colors);
                } else message = "[" + color + "]" + message;
                //updating stats
                pd.lastMessage = Time.millis();
                pd.messageCount++;
                //final sending message, i have my own function for this because people ca have this user muted
                sendChatMessage(player,message);
                return null;
            });

            netServer.admins.addActionFilter( act -> {
                Player player = act.player;
                if (player == null) return true;
                PlayerD pd = Database.getData(player);
                if (pd == null) return true;
                pd.onAction(player);
                Rank rank = getRank(pd);
                //taping on tiles is ok.
                if(act.type == mindustry.net.Administration.ActionType.tapTile) return true;
                //this is against Ag client messing up game
                //if there is emergency
                if(emergency.isActive() && rank.permission.getValue() < Perm.high.getValue()) {
                    sendErrMessage(player,"at-least-verified",Rank.verified.getName());
                    return false;
                }
                TileInfo ti = data[act.tile.y][act.tile.x];
                //if player has to low permission to interact
                if(rank.permission.getValue() < ti.lock){
                    if(rank==Rank.griefer){
                        sendErrMessage(player,"griefer-no-perm");
                    }else {
                        sendErrMessage(player,"at-least-verified",Rank.verified.getName());
                    }
                    return false;

                }
                if(rank.permission.getValue() < Rank.candidate.permission.getValue() && !(act.tile.block() instanceof StaticTree)){
                    ArrayList<Action> acts = undo.computeIfAbsent(pd.uuid, k -> new ArrayList<>());
                    long now = Time.millis();
                    switch (act.type) {
                        case breakBlock:
                            if(act.tile.entity != null && !world.getMap().rules().bannedBlocks.contains(act.tile.block())){
                                Action.addBuildBreak(acts, new Action.Break() {
                                    {
                                        by = pd.uuid;
                                        tile = act.tile;
                                        block = act.tile.block();
                                        config = act.tile.entity.config();
                                        rotation = act.tile.rotation();
                                        age = now;
                                    }
                                });
                            }
                            break;
                        case placeBlock:
                            Action.addBuildBreak(acts, new Action.Build() {
                                {
                                    by = pd.uuid;
                                    tile = act.tile;
                                    block = act.block;
                                    age = now;
                                }
                            });
                            break;
                        case depositItem:
                        case withdrawItem:
                            ArrayList<Long> draws = recentWithdraws.computeIfAbsent(pd.uuid, k -> new ArrayList<>());
                            if (draws.size() > Global.limits.withdrawLimit) {
                                Long ban = banned.contains(player);
                                if (ban != null) {
                                    if (ban > 0) {
                                        sendErrMessage(player, "ag-cannot-withdraw", milsToTime(ban));
                                        return false;
                                    } else {
                                        draws.clear();
                                    }
                                } else {
                                    banned.put(player.uuid, now);
                                    return false;
                                }
                            } else {
                                for (Long l : new ArrayList<>(draws)) {
                                    if (Time.timeSinceMillis(l) > 1000) {
                                        draws.remove(0);
                                    }
                                }
                                draws.add(now);
                            }
                            break;
                        case configure:
                            if (act.tile.entity != null) {
                                acts.add(0, new Action.Configure() {
                                    {
                                        block = act.tile.block();
                                        config = act.tile.entity.config();
                                        newConfig = act.config;
                                        tile = act.tile;
                                        age = now;
                                    }
                                });
                            }
                            break;
                        case rotate:
                            acts.add(0, new Action.Rotate() {
                                {
                                    block = act.tile.block();
                                    rotation = act.tile.rotation();
                                    newRotation = (byte) act.rotation;
                                    tile = act.tile;
                                    age = now;
                                }
                            });
                    }
                    if(acts.size() > undoCache){
                        acts.remove(undoCache);
                    }
                    if(!acts.isEmpty()){
                        int burst = 0;
                        Tile currTile = acts.get(0).tile;
                        int actPerTile = 0;
                        for(Action a :acts){
                            if(Time.timeSinceMillis(a.age) < Global.limits.rateLimitPeriod && !(a instanceof Action.Build || a instanceof Action.Break)) {
                                if(a.tile == currTile){
                                    actPerTile++;
                                } else {
                                    currTile = a.tile;
                                    actPerTile = 0;
                                }
                                if(actPerTile > Global.limits.countedActionsPerTile){
                                    continue;
                                }
                                burst ++;
                            } else {
                                break;
                            }
                        }
                        if (burst > Global.limits.configLimit){
                            Bot.onRankChange(pd.originalName, pd.serverId, rank.name(), Rank.griefer.name(), "Server", "auto");
                            Database.setRank(pd, Rank.griefer, player);
                            for(Action a: acts) {
                                a.Undo();
                            }
                            acts.clear();
                        }
                    }
                }
                //remember tis action for inspect.
                ti.add(act.type.name(),pd);
                ti.lock = Mathf.clamp(rank.permission.getValue(), 0, 1);
                return true;

            });
        });


    }

    private void handleInspect(Player player, Tile tile){
        if (!Database.hasEnabled(player, Setting.inspect)) return;
        Long pn = doubleClicks.contains(player);
        if(pn == null || pn < 0) {
            doubleClicks.add(player);
            return;
        }
        StringBuilder msg = new StringBuilder();
        TileInfo ti = data[tile.y][tile.x];
        if (ti.data.isEmpty()) {
            msg.append("No one interacted with this tile.");
        } else {
            msg.append(ti.lock == 1 ? Rank.verified.getName() + "\n" : "");
            for (String s : ti.data.keySet()) {
                msg.append("[orange]").append(s).append(":[gray]");
                for (PlayerD pd : ti.data.get(s)){
                    msg.append(pd.serverId).append("=").append(pd.originalName).append("|");
                }
                msg.delete(msg.length() - 2, msg.length() - 1);
                msg.append("\n");
            }
        }
        player.sendMessage(msg.toString().substring(0, msg.length() - 1));
    }

    @Override
    public String getMessage(PlayerD pd) {
        return emergency.getReport(pd);
    }

    @Override
    public void onTick() {
        emergency.onTick();
    }



    static class CommandUser{
        static HashMap<String,CommandUser> map = new HashMap<>();
        int commandUseLimit=5;
        String uuid;
        int used=1;
        Timer.Task thread;


        private void addOne(Player player){
            if(Database.hasPerm(player,Perm.high)) return;
            used+=1;
            if(used>=commandUseLimit){
                netServer.admins.addSubnetBan(player.con.address.substring(0,player.con.address.lastIndexOf(".")));
                kick(player,"kick-spamming",0);
                terminate();
                return;
            }
            if (used>=2){
                sendMessage(player,"warming-spam",String.valueOf(commandUseLimit-used));
            }
        }

        private void terminate(){
            map.remove(uuid);
            thread.cancel();
        }

        private CommandUser(Player player) {
            uuid = player.uuid;
            map.put(uuid, this);
            thread=Timer.schedule(()->{
                used--;
                if (used == 0) {
                    terminate();
                }
            }, 2, 2);
        }
    }

    public static class Emergency{
        int time;
        boolean red;
        boolean permanent;

        public Emergency(int min){
            if(min == -1) permanent = true;
            time = 60 * min;
        }

        public boolean isActive(){
            return time > 0 || permanent;
        }

        public String getReport(PlayerD pd){
            if(permanent){
                return format(getTranslation(pd,"emergency-permanent"),Rank.verified.getName());
            }
            if(time <= 0){
                return null;
            }
            String left = secToTime(time);
            return format(getTranslation(pd,"emergency"),left,left);
        }

        public void onTick(){
            if(time <= 0) return;
            time--;
            red = !red;
        }
    }

    public static abstract class RecentMap extends HashMap<String, Long>{
        String endMessage;

        public RecentMap(String endMessage){
            this.endMessage = endMessage;
        }

        public abstract long getPenalty();

        public void add(Player player){
            String uuid = player.uuid;
            put(uuid,Time.millis());
            Timer.schedule(()->{
                if(endMessage == null) return;
                Player found = playerGroup.find(p -> p.uuid.equals(uuid));
                if(found == null) return;
                sendMessage(found, endMessage);
            }, getPenalty()/1000f);
        }

        public Long contains(Player player){
            Long res = get(player.uuid);
            if(res == null) return null;
            res = getPenalty() - Time.timeSinceMillis(res);
            if( res < 0) {
                remove(player.uuid);
            }
            return res;
        }
    }
}

