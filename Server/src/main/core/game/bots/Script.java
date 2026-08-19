package core.game.bots;

import content.data.Quests;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public abstract class Script {

    public ScriptAPI scriptAPI;
    public ArrayList<Item> inventory = new ArrayList<>(20);
    public ArrayList<Item> equipment = new ArrayList<>(20);
    public Map<Integer, Integer> skills = new HashMap<>();
    public ArrayList<Quests> quests = new ArrayList<>(20);


    public Player bot;

    public boolean running = true;
    public boolean endDialogue = true;
    public boolean useRandomIdle = true;

    /**
     * Food id the bot should eat mid-combat. BotScriptPulse checks this every
     * pulse tick (unlike tick(), which is paused while a CombatPulse runs),
     * so setting this gives the bot true in-fight eating. Null disables it.
     */
    public Integer combatFoodId = null;

    /**
     * Percent chance the mid-combat eat attempt actually happens — humans
     * mistime eats under pressure, and bots that eat with 100% reliability
     * are effectively unkillable in even fights. Default 100 (perfect).
     */
    public int combatEatReliability = 100;

    /**
     * Percent chance the panic combo-eat (main food + combo food in one tick)
     * actually comes off — fumbling the emergency heal is what opens KO
     * windows. Default 100 (perfect).
     */
    public int comboEatReliability = 100;

    /**
     * Eat-or-attack decision, consulted by the mid-combat eat hook before
     * feeding the bot. Eating already costs attack time (ScriptAPI.eat
     * delays the next swing by 3 ticks, handing the opponent free hits),
     * so this is where a script chooses to skip the heal and keep the
     * pressure on — e.g. pressing a KO on a nearly-dead victim. Returning
     * false skips this eat attempt.
     */
    public boolean shouldCombatEat() { return true; }

    /**
     * Called every BotScriptPulse tick while the bot's combat pulse is
     * attacking — unlike tick(), which is paused during combat. Scripts
     * override this for in-fight decision making (KO weapon swaps, specials,
     * smite, emergency eating).
     */
    public void combatTick() {}

    public void init(boolean isPlayer)
    {
        //bot.init();
        scriptAPI = new ScriptAPI(bot);

        if(!isPlayer) {
            // Skills and quests need to be set before equipment in case equipment has level or quest requirements
            for (Map.Entry<Integer, Integer> skill : skills.entrySet()) {
                setLevel(skill.getKey(), skill.getValue());
            }
            for (Quests quest : quests) {
                bot.getQuestRepository().setStage(bot.getQuestRepository().getQuest(quest), 100);
            }
            for (Item i : equipment) {
                bot.getEquipment().add(i, true, false);
            }
            bot.getInventory().clear();
            for (Item i : inventory) {
                bot.getInventory().add(i);
            }
        }
    }

    @Override
    public String toString() {
        return bot.getName() + " is a " + this.getClass().getSimpleName() + " at location " + bot.getLocation().toString() + " Current pulse: " + bot.getPulseManager().getCurrent();
    }

    public abstract void tick();

    /**
     * Returns a human-readable diagnostic state of this script for the telemetry API.
     * Scripts that track their goal in a field named "state" (typically a nested State enum,
     * as most content bot scripts do) are picked up automatically via reflection.
     * Scripts without such a field fall back to the script class name.
     * Override this for richer output (see Adventurer for an example).
     */
    public String getDiagnosticState() {
        try {
            java.lang.reflect.Field field = getClass().getDeclaredField("state");
            field.setAccessible(true);
            Object value = field.get(this);
            if (value != null) {
                return value.toString();
            }
        } catch (NoSuchFieldException ignored) {
            // Script has no state field - fall through to the default.
        } catch (IllegalAccessException ignored) {
            // Cannot happen after setAccessible(true) on our own classpath - fall through.
        }
        return getClass().getSimpleName();
    }

    public void setLevel(int skill, int level) {
        bot.getSkills().setLevel(skill, level);
        bot.getSkills().setStaticLevel(skill, level);
        bot.getSkills().updateCombatLevel();
        bot.getAppearance().sync();
    }

    // This does not get called and all implementations should be removed
    public abstract Script newInstance();
}
