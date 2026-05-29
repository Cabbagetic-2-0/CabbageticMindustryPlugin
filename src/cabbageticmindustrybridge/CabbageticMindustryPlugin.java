/* * Cabbagetic Mindustry Bridge (v8)
 * Developed by: Esterajisi (Cabbagetic-Classic/ Cabbagetic)
 * * Credits & Acknowledgements:
 * - Base Template: MindustryPluginTemplate by Anuken
 * - Logic Concepts: AuthorizePlugin by Anuken
 * - Original Work: CabbageticMindustryPlugin
 * * "Respect the code, credit the source."
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */ 

package cabbageticmindustrybridge;

import arc.*;
import arc.files.Fi;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import arc.util.serialization.JsonWriter.OutputType;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.net.Administration.*;
import mindustry.type.*;
import mindustry.world.blocks.storage.*;
import mindustry.game.Team;
import mindustry.game.Gamemode;
import arc.struct.ObjectMap.*;
import arc.struct.ObjectMap;

public class CabbageticMindustryPlugin extends Plugin {
    private ConfigData config;
    private Json json = new Json();
    private arc.files.Fi configFile;
    private long lastThoriumAlert = 0;
    private long startTime;
    
    public static final float messageSpacing = 60f;
    private ObjectSet<String> deauthorized = new ObjectSet<>();
    private ObjectFloatMap<Player> messageTime = new ObjectFloatMap<>();
    private String message = "[scarlet]You are not authorized to perform this action.";
    private boolean authUnits = true;
    private ObjectMap<String, Long> goCooldowns = new ObjectMap<>();

    // Complete tracking structure unified inside Config
    public static class ConfigData {
        public String botToken = "REPLACE_ME_OR_LEAVE_EMPTY";
        public String webhookUrl = "REPLACE_ME_OR_LEAVE_EMPTY";
        public String discordInvite = "YOUR_DISCORD_INVITE_HERE";
        public String[] bannedWords = {"badword1", "badword2", "meanverb"};
        public ObjectSet<String> opAdmins = new ObjectSet<>();
        
        public ConfigData() {}
    }
    
    @Override
    public void init() {
        startTime = Time.millis();
        
        // Load Config directly from JSON file
        configFile = Core.settings.getDataDirectory().child("mods/CabbageticMindustryPluginConfig.json");
        if (!configFile.exists()) {
            config = new ConfigData();
            json.setOutputType(OutputType.json);
            json.setUsePrototypes(false);
            String result = json.prettyPrint(config);
            if(result.equals("{}") || result.equals("")) {
                result = "{\n  \"webhookUrl\": \"REPLACE_ME\",\n  \"discordInvite\": \"YOUR_DISCORD_INVITE_HERE\",\n  \"bannedWords\": [\"badword1\", \"badword2\"],\n  \"opAdmins\": []\n}";
            }
            configFile.writeString(result);
            Log.info("Cabbagetic: Config file initialized.");
        } else {
            config = json.fromJson(ConfigData.class, configFile.readString());
            if (config.opAdmins == null) {
                config.opAdmins = new ObjectSet<>();
            }
        }

        sendToDiscord(":white_check_mark: **Server is Online!**");
        
        // Chat Logging Event
        Events.on(PlayerChatEvent.class, event -> {
            if (!event.message.startsWith("/")) {
                sendToDiscord("**" + event.player.name + "**: " + event.message);
            }
        });

        // Safe Persistent Join Listener
        Events.on(PlayerJoin.class, event -> {
            sendToDiscord(":inbox_tray: **" + event.player.name + "** joined the server.");
            String playerUuid = event.player.usid();
            
            // Checking the single source of truth: our Config file's map
            if(config.opAdmins.contains(playerUuid)){
                event.player.admin = true;
                Log.info("Auto-opAdmined persistent user: " + event.player.name);
            }
        });

        Events.on(PlayerLeave.class, event -> {
            sendToDiscord(":outbox_tray: **" + event.player.name + "** left the server.");
        });

        // Thorium Alert Monitor
        Events.on(BuildSelectEvent.class, event -> {
            if(!event.breaking && event.builder != null && event.builder.buildPlan().block == Blocks.thoriumReactor){
                long now = Time.millis();
                if(now - lastThoriumAlert > 1000 * 60 * 5){
                    Player player = event.builder.getPlayer();
                    if(player != null) {
                        sendToDiscord(":warning: **" + player.name + "** is building a Thorium Reactor!");
                        Call.sendMessage("[scarlet]NUCLEAR ALERT![] " + player.name + " is building a reactor!");
                    }
                    lastThoriumAlert = now;
                }
            }
        });
        
        // Case-insensitive Filter Rules
        Vars.netServer.admins.addChatFilter((player, text) -> {
            String filteredText = text;
            for(String word : config.bannedWords){
                filteredText = filteredText.replaceAll("(?i)" + word, "____");
            }
            return filteredText;
        });

        Vars.netServer.admins.addActionFilter(action -> {
            if(action.type == ActionType.depositItem && action.item == Items.blastCompound && action.tile.block() instanceof CoreBlock){
                if(action.player != null) action.player.sendMessage("[pink]Filter:[] Blast compound cannot be put in the core!");
                return false;
            }
            return true;
        });

        // Fallbacks for default settings profile
        deauthorized = Core.settings.getJson("deauthorized-list", ObjectSet.class, String.class, ObjectSet::new);
        message = Core.settings.getString("authorized-message", "[scarlet]You are not authorized to perform this action.");
        authUnits = Core.settings.getBool("allow-unauthorized-units", authUnits);

        Vars.netServer.admins.addActionFilter(action -> {
            if(action.player == null) return true;
            if(action.player.admin) return true;

            if(deauthorized.contains(action.player.usid())){
                if(authUnits && (action.type == ActionType.control || action.type == ActionType.command)) return true;
                if(action.type == ActionType.control && action.unit == null) return true;

                message(action.player);
                return false;
            }
            return true;
        });
        
        Log.info("Cabbagetic Plugin Loaded Successfully. Special thanks to Anuken for the template!");
    }

    private void sendToDiscord(String message) {
        if(config.webhookUrl == null || config.webhookUrl.contains("REPLACE_ME")) return;
        
        Http.post(config.webhookUrl)
            .content("{\"content\": \"" + message + "\"}")
            .header("Content-Type", "application/json")
            .submit(result -> {});
    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("reactors", "List all thorium reactors in the map.", args -> {
            for(int x = 0; x < Vars.world.width(); x++){
                for(int y = 0; y < Vars.world.height(); y++){
                    if(Vars.world.tile(x, y).block() == Blocks.thoriumReactor && Vars.world.tile(x, y).isCenter()){
                        Log.info("Reactor at @, @", x, y);
                    }
                }
            }
        });

        handler.register("unauth", "<add/remove> <player...>", "Unauthorize or authorize player by name or UUID.", arg -> {
            Player player = Groups.player.find(p -> p.uuid().equals(arg[1]) || Strings.stripColors(p.name).equals(Strings.stripColors(arg[1])));
            if(arg[0].equals("add")){
                if(player != null){
                    deauthorized.add(player.usid());
                    Log.info("Un-authorized: @", player.name);
                    save();
                }else{
                    Log.err("Player not found.");
                }
            }else if(arg[0].equals("remove")){
                if(player != null){
                    deauthorized.remove(player.usid());
                    Log.info("Authorized: @", player.name);
                    save();
                }else{
                    Log.err("Player not found.");
                }
            }else{
                Log.err("Incorrect usage. First argument must be 'add' or 'remove'.");
            }
        });

        handler.register("auth-message", "[message...]", "Set the message displayed when someone is not authorized to perform an action.", arg -> {
            if(arg.length > 0){
                message = arg[0];
                save();
                Log.info("Message set.");
            }else{
                Log.info("Current message: @", message);
            }
        });

        handler.register("auth-units", "[yes/no]", "Set whether unauthorized players are able to control units. Default: yes.", arg -> {
            if(arg.length > 0){
                authUnits = arg[0].equals("yes");
                save();
                Log.info("auth-units set to '@'.", arg[0].equals("yes") ? "yes" : "no");
            }else{
                Log.info("Current value: @", authUnits ? "yes" : "no");
            }
        });
    }

    @Override
    public void registerClientCommands(CommandHandler handler){
        handler.<Player>register("reply", "<text...>", "Echoes text.", (args, player) -> {
            player.sendMessage("You said: [accent] " + args[0]);
        });

        //register a whisper command which can be used to send other players messages
        handler.<Player>register("whisper", "<player> <text...>", "Whisper text to another player.", (args, player) -> {
            //find player by name
            Player other = Groups.player.find(p -> p.name.equalsIgnoreCase(args[0]));

            //give error message with scarlet-colored text if player isn't found
            if(other == null){
                player.sendMessage("[scarlet]No player by that name found!");
                return;
            }

            //send the other player a message, using [lightgray] for gray text color and [] to reset color
            other.sendMessage("[lightgray](whisper) " + player.name + ":[] " + args[1]);
        });

        handler.<Player>register("discord", "Get the Discord link.", (args, player) -> {
            player.sendMessage("[sky]Join our Discord: [white]" + config.discordInvite);
        });

        handler.<Player>register("rules", "Read server rules.", (args, player) -> {
            player.sendMessage("[orange]RULES:\n1. No Griefing\n2. No Swearing\n3. Have Fun!");
        });

        handler.<Player>register("sos", "<reason...>", "Send emergency help to admins.", (args, player) -> {
            sendToDiscord("🚨 <@&1502688155759939736> **SOS from " + player.name + "**: " + args[0]);
            player.sendMessage("[green]Admins have been notified!");
        });

        handler.<Player>register("uptime", "Check server uptime.", (args, player) -> {
            long uptime = (Time.millis() - startTime) / 1000;
            player.sendMessage("[accent]Uptime: [white]" + (uptime / 60) + " minutes");
        });

        handler.<Player>register("currentmap", "Show current map status.", (args, player) -> {
            player.sendMessage("[accent]Map: [white]" + Vars.state.map.name() + " [accent]| Players: [white]" + Groups.player.size());
        });

        handler.<Player>register("maps", "List available maps.", (args, player) -> {
            StringBuilder sb = new StringBuilder("[accent]Available Maps:\n");
            Vars.maps.all().each(m -> sb.append("[white]- ").append(m.name()).append("\n"));
            player.sendMessage(sb.toString());
        });

        // Fully patched stable transition command
        handler.<Player>register("go", "[mapName...]", "Switch maps seamlessly.", (args, player) -> {
            if(args.length == 0){
                if(!player.admin){
                    player.sendMessage("[scarlet]Only admins can force an instant GameOver.");
                    return;
                }
                Events.fire(new GameOverEvent(Team.crux));
                return;
            }

            String mapName = args[0];
            long lastUse = goCooldowns.get(player.usid(), 0L);
        
            if(!player.admin && Time.millis() - lastUse < 300000){
                long remaining = (300000 - (Time.millis() - lastUse)) / 1000;
                player.sendMessage("[scarlet]Wait " + remaining + "s before changing maps again.");
                return;
            }

            mindustry.maps.Map found = Vars.maps.all().find(m -> m.name().equalsIgnoreCase(mapName));
            if(found != null){
                goCooldowns.put(player.usid(), Time.millis());
                Call.sendMessage("[accent]" + player.name + "[white] is switching the map to [accent]" + found.name());
                
                // Using a safe async scheduling post with atomic execution blocks
                Core.app.post(() -> {
                    Vars.logic.reset();
                    
                    // Yield execution for 1 game tick to allow network buffers to clear
                    Time.run(1f, () -> {
                        Vars.world.loadMap(found);
                        Vars.state.rules.attackMode = true;
                        Vars.logic.play();
                        
                        // Explicit cleanly scheduled synchronization loop
                        for(Player p : Groups.player){
                            Vars.netServer.sendWorldData(p);
                        }
                        Log.info("Map successfully changed to: " + found.name());
                    });
                });
            } else {
                player.sendMessage("[scarlet]Map not found: " + mapName);
            }
        });

        // Saved permanently inside JSON config map array
        handler.<Player>register("admin-save", "Save your UUID as an opAdmin.", (args, player) -> {
            if(player.admin){
                config.opAdmins.add(player.usid());
                saveConfig();
                player.sendMessage("[green]Your device USID has been permanently whitelisted to the JSON config file!");
            }
        });

        handler.<Player>register("admin-remove", "<UUID/Name>", "Remove opAdmin status.", (args, player) -> {
            if(!player.admin){
                player.sendMessage("[scarlet]Only admins can use this command.");
                return;
            }

            String target = args[0];
            
            // Direct config target key lookup
            if(config.opAdmins.contains(target)) {
                config.opAdmins.remove(target);
                saveConfig();
                player.sendMessage("[yellow]UUID configuration key wiped cleanly.");
                return;
            }

            Player found = Groups.player.find(p -> p.name.equalsIgnoreCase(target) || p.usid().equals(target));
            if(found != null){
                config.opAdmins.remove(found.usid());
                found.admin = false;
                saveConfig();
                player.sendMessage("[green]Removed " + found.name + " from local runtime lists and saved.");
            } else {
                player.sendMessage("[scarlet]Player identifier target not found.");
            }
        });
    }

    private void message(Player player){
        if(Time.time - messageTime.get(player, 0) >= messageSpacing){
            player.sendMessage(message);
            messageTime.put(player, Time.time);
        }
    }

    private void save(){
        Core.settings.put("authorized-message", message);
        Core.settings.put("allow-unauthorized-units", authUnits);
        Core.settings.putJson("deauthorized-list", ObjectSet.class, deauthorized);
    }

    private void saveConfig() {
        if(configFile != null && config != null) {
            configFile.writeString(json.prettyPrint(config));
        }
    }
}
