/* * Cabbagetic Mindustry Bridge (v8)
 * Developed by: Esterajisi (Cabbagetic / Cabbagetic-2-0)
 * * Credits & Acknowledgements:
 * - Base Template: MindustryPluginTemplate by Anuken
 * - Logic Concepts: AuthorizePlugin by Anuken
 * - Original Work: CabbageticMindustryPlugin
 * * "Respect the code, credit the source."
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
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.net.Administration.*;
import mindustry.world.blocks.storage.*;

public class CabbageticMindustryPlugin extends Plugin{
    private ConfigData config;
    private Json json = new Json();
    private arc.files.Fi configFile;

    //The Config Structure (Inside the main class)
    public static class ConfigData {
        public String botToken = "REPLACE_ME";
        public String channelId = "00000000000000";
        public String webhookUrl = "REPLACE_ME_OR_LEAVE_EMPTY";
        public String[] bannedWords = {"badword1", "badword2", "meanverb"};
        
        public ConfigData() {}
    }
    
    @Override
    //called when game initializes
    public void init() {
        //Load or Create Config
        configFile = Core.settings.getDataDirectory().child("mods/CabbageticMindustryPluginConfig.json");
        if (!configFile.exists()) {
            config = new ConfigData();
        json.setOutputType(OutputType.json);
        json.setUsePrototypes(false);
                String result = json.prettyPrint(config);
        if(result.equals("{}") || result.equals("")) {
            result = "{\n  \"webhookUrl\": \"REPLACE_ME\",\n  \"bannedWords\": [\"badword1\", \"badword2\"]\n}";
        }
        
        configFile.writeString(result);
        Log.info("Cabbagetic: Config file initialized.");
        } else {
            config = json.fromJson(ConfigData.class, configFile.readString());
        }
        
        // Listen for player chat events
        Events.on(PlayerChatEvent.class, event -> {
            // Filter out commands (starting with /)
            if (!event.message.startsWith("/")) {
                sendToDiscord("**" + event.player.name + "**: " + event.message);
            }
        });

        //Event Listeners (Join/Leave/Commands)
        Events.on(PlayerJoin.class, event -> {
            sendToDiscord(":inbox_tray: **" + event.player.name + "** joined the server.");
        });

        Events.on(PlayerLeave.class, event -> {
            sendToDiscord(":outbox_tray: **" + event.player.name + "** left the server.");
        });

        //listen for a block selection event - Thorium Alert from before (Optional)
        Events.on(BuildSelectEvent.class, event -> {
            if(!event.breaking && event.builder != null && event.builder.buildPlan() != null && event.builder.buildPlan().block == Blocks.thoriumReactor && event.builder.isPlayer()){
                //player is the unit controller
                Player player = event.builder.getPlayer();
                sendToDiscord(":warning: **" + player.name + "** is building a Thorium Reactor!");
                //send a message to everyone saying that this player has begun building a reactor
                Call.sendMessage("[scarlet]ALERT![] " + player.name + " has begun building a reactor at " + event.tile.x + ", " + event.tile.y);
            }
        });

        Log.info("Cabbagetic Plugin Loaded. Special thanks to Anuken for the template!");

        //Chat Filter (Using words from the JSON)
        Vars.netServer.admins.addChatFilter((player, text) -> {
            String filteredText = text;
            for(String word : config.bannedWords){
                // (?i) makes it case-insensitive
                filteredText = filteredText.replaceAll("(?i)" + word, "#$!@");
            }
            return filteredText;
        });

        //add an action filter for preventing players from doing certain things
        Vars.netServer.admins.addActionFilter(action -> {
            //random example: prevent blast compound depositing
            if(action.type == ActionType.depositItem && action.item == Items.blastCompound && action.tile.block() instanceof CoreBlock){
                action.player.sendMessage("[pink]Filter:[] Blast compound cannot be put in the core!");
                return false;
            }
            return true;
        });

        authorized = Core.settings.getJson("authorized-players", ObjectSet.class, String.class, ObjectSet::new);
        message = Core.settings.getString("authorized-message", message);
        authUnits = Core.settings.getBool("allow-unauthorized-units", true);

        Vars.netServer.admins.addActionFilter(action -> {
            if(action.player == null) return true;
            if(action.player.admin || authorized.contains(action.player.usid()) ||
                (authUnits && (action.type == ActionType.control || action.type == ActionType.command)) || //check if they can command units
                (action.type == ActionType.control && action.unit == null) //make sure they can un-control units
            ){
                return true;
            }else{
                message(action.player);
                return false;
            }
        });
    }

    private void sendToDiscord(String message) {
        // Check if Webhook is set up
        if(config.webhookUrl == null || config.webhookUrl.contains("REPLACE_ME")) return;
        
        // Use Mindustry's internal Http helper
        Http.post(config.webhookUrl)
            .content("{\"content\": \"" + message + "\"}")
            .header("Content-Type", "application/json")
            .submit(result -> {
                // This runs in the background to prevent game lag
            });
    }
  
    public static final float messageSpacing = 60f;

    private ObjectSet<String> authorized = new ObjectSet<>();
    private ObjectFloatMap<Player> messageTime = new ObjectFloatMap<>();
    private String message = "[scarlet]You are not authorized to perform this action.";
    private boolean authUnits = true;

    //register commands that run on the server
    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("reactors", "List all thorium reactors in the map.", args -> {
            for(int x = 0; x < Vars.world.width(); x++){
                for(int y = 0; y < Vars.world.height(); y++){
                    //loop through and log all found reactors
                    //make sure to only log reactor centers
                    if(Vars.world.tile(x, y).block() == Blocks.thoriumReactor && Vars.world.tile(x, y).isCenter()){
                        Log.info("Reactor at @, @", x, y);
                    }
                }
            }
        });

        handler.register("auth", "<add/remove> <player...>", "Authorize or unauthorize player by name or UUID.", arg -> {
            Player player = Groups.player.find(p -> p.uuid().equals(arg[1]) || Strings.stripColors(p.name).equals(Strings.stripColors(arg[1])));
            if(arg[0].equals("add")){
                if(player != null){
                    authorized.add(player.usid());
                    Log.info("Authorized: @", player.name);
                    save();
                }else{
                    Log.err("Player not found. Note that they must be online for authorization to work.");
                }
            }else if(arg[0].equals("remove")){
                if(player != null){
                    authorized.remove(player.usid());
                    Log.info("Un-authorized: @", player.name);
                    save();
                }else{
                    Log.err("Player not found. Note that they must be online for authorization to work.");
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

    //register commands that player can invoke in-game
    @Override
    public void registerClientCommands(CommandHandler handler){

        //register a simple reply command
        handler.<Player>register("reply", "<text...>", "A simple ping command that echoes a player's text.", (args, player) -> {
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
        Core.settings.putJson("authorized-list", String.class, authorized);
    }
}
