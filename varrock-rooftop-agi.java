package com.xploithq.agility;

import org.osbot.rs07.api.map.Area;
import org.osbot.rs07.api.map.Position;
import org.osbot.rs07.api.model.GroundItem;
import org.osbot.rs07.api.model.RS2Object;
import org.osbot.rs07.api.ui.Skill;
import org.osbot.rs07.api.ui.Tab;
import org.osbot.rs07.input.mouse.MiniMapTileDestination;
import org.osbot.rs07.script.Script;
import org.osbot.rs07.script.ScriptManifest;
import org.osbot.rs07.utility.ConditionalSleep;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Varrock Rooftops – xploitHQ edition (hardened)
 * - State machine flow
 * - Segment-aware obstacle selection
 * - Robust action click (L+R click fallback, menu interaction)
 * - Mark of grace priority (safe proximity, confirmation)
 * - Stuck recovery: camera wiggle, tile nudge, web-walk back to start
 * - Stamina & food support
 * - Antiban sprinkles (camera wiggles, tab peeks, mouse offscreen, hover-next)
 * - Paint overlay: XP/hr, laps/hr, marks/hr, runtime
 * - Null-safety + OSBot version tolerance guards
 */
@ScriptManifest(
        name = "Varrock Agility+ (xploitHQ)",
        author = "xploitHQ",
        version = 2_2,
        info = "Varrock rooftop with antiban, stamina, paint, and stuck recovery"
)
public class VarrockAgility extends Script {

    // ============================= CONFIG =============================
    private boolean STEALTH = true;               // human-ish delays
    private boolean LOGGING = true;
    private boolean LOOT_MARKS = true;            // pick Mark of grace
    private boolean USE_STAMINA = true;           // sip stamina when run < threshold
    private int     STAMINA_THRESHOLD = 20;       // run energy %
    private String  STAMINA_NAME = "Stamina potion";
    private boolean USE_FOOD = false;             // eat when HP below threshold
    private String  FOOD_NAME = "Cake";           // change if you enable food
    private int     EAT_HP = 15;                  // eat at/under this HP
    private boolean HOVER_NEXT = true;            // hover next obstacle
    private boolean PAINT = true;                 // toggle overlay

    // ============================= COURSE =============================
    private enum Segment {
        START_0(new Area(3219, 3417, 3223, 3413, 0)),
        GAP_1  (new Area(3209, 3417, 3216, 3411, 3)),
        ROPE_2 (new Area(3207, 3418, 3211, 3414, 3)),
        GAP_3  (new Area(3195, 3420, 3199, 3416, 3)),
        GAP_4  (new Area(3188, 3427, 3192, 3423, 3)),
        GAP_5  (new Area(3200, 3431, 3204, 3427, 3)),
        LEDGE_6(new Area(3207, 3425, 3211, 3421, 3)),
        EDGE_7 (new Area(3216, 3418, 3220, 3414, 3));
        final Area area;
        Segment(Area a) { this.area = a; }
    }

    private static final class Obstacle {
        final String[] names;     // accept multiple client strings
        final String[] actions;   // accept multiple action verbs
        final Position pos;
        final Segment seg;
        Obstacle(Segment seg, Position p, String[] names, String... actions) {
            this.seg = seg; this.pos = p; this.names = names; this.actions = actions;
        }
    }

    private final List<Obstacle> COURSE = Arrays.asList(
            new Obstacle(Segment.START_0, new Position(3221, 3414, 0),
                    new String[]{"Rough wall", "Rough Wall"}, "Climb"),
            new Obstacle(Segment.GAP_1, new Position(3213, 3414, 3),
                    new String[]{"Gap"}, "Leap", "Jump"),
            new Obstacle(Segment.ROPE_2, new Position(3209, 3416, 3),
                    new String[]{"Tightrope"}, "Cross", "Walk-across"),
            new Obstacle(Segment.GAP_3, new Position(3197, 3418, 3),
                    new String[]{"Gap"}, "Leap", "Jump"),
            new Obstacle(Segment.GAP_4, new Position(3190, 3425, 3),
                    new String[]{"Gap"}, "Leap", "Jump"),
            new Obstacle(Segment.GAP_5, new Position(3202, 3429, 3),
                    new String[]{"Gap"}, "Leap", "Jump"),
            new Obstacle(Segment.LEDGE_6, new Position(3209, 3423, 3),
                    new String[]{"Ledge"}, "Balance", "Cross"),
            new Obstacle(Segment.EDGE_7, new Position(3218, 3416, 3),
                    new String[]{"Edge"}, "Jump-off", "Jump")
    );

    // ============================= RUNTIME =============================
    private long startTime, lastProgress;
    private int startXp, lastLapZ, lapsDone = 0, marksCount = 0;
    private final DecimalFormat df1 = new DecimalFormat("#,###");
    private final DecimalFormat df2 = new DecimalFormat("#,###.0");

    // ============================= LIFECYCLE =============================
    @Override
    public void onStart() {
        startTime = System.currentTimeMillis();
        startXp = getSkills().getExperience(Skill.AGILITY);
        lastProgress = System.currentTimeMillis();
        lastLapZ = myPosition().getZ();
        logf("Started. Agility level: %d", getSkills().getDynamic(Skill.AGILITY));
    }

    @Override
    public void onExit() {
        logf("Finished. Laps: %d, Marks: %d, XP gained: %s",
                lapsDone, marksCount,
                df1.format(getSkills().getExperience(Skill.AGILITY) - startXp));
    }

    @Override
    public int onLoop() throws InterruptedException {
        if (USE_FOOD && needEat()) return handleEat();
        if (USE_STAMINA && needStamina()) return handleStamina();

        if (LOOT_MARKS && tryPickupMark()) return rand(350, 650);

        if (isStuck()) return recover();

        if (isIdle()) {
            Obstacle next = getNextObstacle();
            if (next != null) {
                if (HOVER_NEXT) maybeHover(next);
                handleObstacle(next);
                return rand(250, 450);
            }
        }

        maybeAntibanIdle();
        return rand(200, 350);
    }

    // ============================= STATE HELPERS =============================
    private boolean isIdle() {
        return !myPlayer().isAnimating() && !myPlayer().isMoving();
    }

    private boolean isStuck() {
        long now = System.currentTimeMillis();
        if (!isIdle()) { lastProgress = now; return false; }
        return now - lastProgress > 10_000; // idle >10s
    }

    private int recover() throws InterruptedException {
        logf("Stuck detected. Attempting recovery.");
        // 1) Camera wiggle
        getCamera().moveYaw(rand(15, 60));
        getCamera().movePitch(rand(20, 30));
        sleep(rand(200, 400));
        // 2) Step nearby
        Position nudge = myPosition().translate(rand(-2, 2), rand(-2, 2));
        getWalking().walk(nudge);
        new ConditionalSleep(2500, 100) { @Override public boolean condition() { return myPlayer().isMoving(); } }.sleep();
        sleep(rand(300, 600));
        // 3) If fell, return to start on ground
        if (myPosition().getZ() == 0 && !Segment.START_0.area.contains(myPosition())) {
            logf("Returning to start.");
            webTo(new Position(3221, 3414, 0));
        }
        lastProgress = System.currentTimeMillis();
        return rand(300, 600);
    }

    // ============================= MARKS & CONSUMABLES =============================
    private boolean tryPickupMark() throws InterruptedException {
        GroundItem mark = getGroundItems().closest(g ->
                g != null && equalsIgnoreCase(g.getName(), "Mark of grace"));
        if (mark == null) return false;

        if (distToDest(mark.getPosition()) > 2 && myPosition().distance(mark) > 3) {
            walkSmart(mark.getPosition());
            return true;
        }

        if (!mark.isVisible()) getCamera().toEntity(mark);

        if (mark.interact("Take")) {
            if (STEALTH) sleep(rand(200, 450));
            boolean got = new ConditionalSleep(3000, 100) {
                @Override public boolean condition() {
                    return !mark.exists() || getInventory().contains("Mark of grace");
                }
            }.sleep();
            if (got) {
                marksCount++;
                logf("Picked Mark of grace. Total: %d", marksCount);
                lastProgress = System.currentTimeMillis();
            }
            return true;
        }
        return false;
    }

    private boolean needStamina() {
        if (!USE_STAMINA) return false;
        if (getSettings().isRunning() && getSettings().getRunEnergy() >= STAMINA_THRESHOLD) return false;
        return getInventory().getItems().stream()
                .anyMatch(i -> i != null && containsIgnoreCase(i.getName(), "stamina potion"));
    }

    private int handleStamina() throws InterruptedException {
        ensureRunOn();
        if (getSettings().getRunEnergy() < STAMINA_THRESHOLD) {
            if (getInventory().interact("Drink", STAMINA_NAME)) {
                sleep(rand(600, 900));
                logf("Sipped stamina.");
            } else {
                // Any-dose fallback
                getInventory().getItems().stream()
                        .filter(i -> i != null && containsIgnoreCase(i.getName(), "stamina potion"))
                        .findFirst()
                        .ifPresent(i -> {
                            try {
                                if (i.interact("Drink")) {
                                    sleep(rand(600, 900));
                                    logf("Sipped stamina (fallback).");
                                }
                            } catch (InterruptedException ignored) {}
                        });
            }
        }
        return rand(150, 250);
    }

    private void ensureRunOn() throws InterruptedException {
        if (!getSettings().isRunning()) {
            if (!getSettings().setRunning(true)) {
                // fallback: tab poke & retry
                getTabs().open(Tab.INVENTORY);
                sleep(rand(120, 220));
                getSettings().setRunning(true);
            }
            sleep(rand(200, 350));
        }
    }

    private boolean needEat() {
        return getSkills().getDynamic(Skill.HITPOINTS) <= EAT_HP
                && getInventory().contains(FOOD_NAME);
    }

    private int handleEat() throws InterruptedException {
        if (getInventory().interact("Eat", FOOD_NAME)) {
            sleep(rand(450, 700));
            logf("Ate %s. HP: %d", FOOD_NAME, getSkills().getDynamic(Skill.HITPOINTS));
        }
        return rand(200, 300);
    }

    // ============================= OBSTACLES =============================
    private Obstacle getNextObstacle() {
        Position me = myPosition();
        int z = me.getZ();

        // Lap counting: drop from roof to ground & be at start area
        if (lastLapZ == 3 && z == 0 && Segment.START_0.area.contains(me)) {
            lapsDone++;
            lastLapZ = z;
            logf("Lap complete! Laps: %d", lapsDone);
        } else {
            lastLapZ = z;
        }

        // Choose the obstacle with best heuristic score (plane first, then distance, area bonus)
        Obstacle best = null;
        double bestScore = Double.MAX_VALUE;
        for (Obstacle o : COURSE) {
            double planePenalty = Math.abs(o.pos.getZ() - z) * 10.0;
            double dist = me.distance(o.pos);
            boolean inside = o.seg.area.contains(me);
            double segBonus = inside ? -3.0 : 0.0;
            double score = planePenalty + dist + segBonus;
            if (score < bestScore) { bestScore = score; best = o; }
        }
        return best;
    }

    private void handleObstacle(Obstacle o) throws InterruptedException {
        if (myPosition().distance(o.pos) > 6 || myPosition().getZ() != o.pos.getZ()) {
            walkSmart(o.pos);
        }

        RS2Object obj = getObjects().closest(ob ->
                ob != null
                        && ob.getName() != null
                        && nameAny(ob.getName(), o.names)
                        && ob.getPosition() != null
                        && ob.getPosition().getZ() == o.pos.getZ()
                        && ob.getPosition().distance(o.pos) <= 6
        );

        if (obj == null) {
            getCamera().toPosition(o.pos);
            walkSmart(o.pos);
            return;
        }

        if (!obj.isVisible()) {
            getCamera().toEntity(obj);
            if (!obj.isVisible()) {
                getCamera().movePitch(rand(30, 67));
                getCamera().moveYaw(rand(15, 45));
            }
        }

        if (STEALTH) sleep(rand(120, 420));

        boolean clicked = clickWithActions(obj, o.actions);
        if (!clicked) {
            walkSmart(obj.getPosition());
            clicked = clickWithActions(obj, o.actions);
        }

        if (clicked) {
            logf("Clicked: %s", String.join("/", o.names));
            Position before = myPosition();

            // Wait for real progress
            new ConditionalSleep(5500, 120) {
                @Override public boolean condition() {
                    return myPlayer().isAnimating()
                            || myPlayer().isMoving()
                            || myPosition().getZ() != before.getZ()
                            || myPosition().distance(before) > 2;
                }
            }.sleep();

            lastProgress = System.currentTimeMillis();
            if (STEALTH) sleep(rand(280, 620));
        }
    }

    private boolean clickWithActions(RS2Object obj, String... actions) throws InterruptedException {
        // left-click attempts
        for (String a : actions) {
            if (obj.interact(a)) return true;
        }
        // right-click menu fallback
        if (!obj.isVisible()) getCamera().toEntity(obj);
        getMouse().click(false); // right-click
        sleep(rand(120, 220));
        for (String a : actions) {
            if (getMenu().isOpen() && getMenu().interact(a, obj.getName())) return true;
        }
        return false;
    }

    // ============================= MOVEMENT UTILS =============================
    private void walkSmart(Position p) throws InterruptedException {
        if (p == null) return;
        try {
            if (getMap().canReach(p)) {
                getWalking().walk(p);
            } else {
                // Rooftops often say unreachable; minimap nudge
                Position jiggle = jitter(p, 1, 1);
                try {
                    getWalking().walk(new MiniMapTileDestination(getBot(), jiggle));
                } catch (Throwable t) {
                    getWalking().walk(p);
                }
            }
        } catch (Throwable t) {
            // OSBot API variance fallback
            getWalking().walk(p);
        }
        getCamera().toPosition(p);
        new ConditionalSleep(2200, 100) { @Override public boolean condition() { return myPlayer().isMoving(); } }.sleep();
        if (STEALTH) sleep(rand(220, 480));
    }

    private void webTo(Position p) throws InterruptedException {
        getWalking().webWalk(p);
        if (STEALTH) sleep(rand(300, 600));
    }

    private Position jitter(Position p, int dx, int dy) {
        return new Position(p.getX() + rand(-dx, dx), p.getY() + rand(-dy, dy), p.getZ());
    }

    private int distToDest(Position p) {
        Position dest = getMap().getDestination();
        if (dest == null || p == null) return 999;
        return dest.distance(p);
    }

    // ============================= ANTIBAN =============================
    private void maybeAntibanIdle() throws InterruptedException {
        int roll = rand(0, 1000);
        if (roll < 8) {
            getCamera().moveYaw(rand(-40, 40));
            getCamera().movePitch(rand(-10, 10));
        } else if (roll < 12) {
            getTabs().open(Tab.STATS);
            sleep(rand(500, 900));
            getTabs().open(Tab.INVENTORY);
        } else if (roll < 15) {
            getMouse().moveOutsideScreen();
            sleep(rand(600, 1200));
        }
    }

    private void maybeHover(Obstacle next) {
        if (next == null) return;
        RS2Object obj = getObjects().closest(ob ->
                ob != null && ob.getName() != null && nameAny(ob.getName(), next.names));
        if (obj != null && obj.isVisible() && obj.getCenterPoint() != null) {
            getMouse().move(obj.getCenterPoint());
        }
    }

    // ============================= PAINT =============================
    @Override
    public void onPaint(Graphics2D g) {
        if (!PAINT) return;

        long rt = System.currentTimeMillis() - startTime;
        int xpGained = getSkills().getExperience(Skill.AGILITY) - startXp;
        double hrs = Math.max(0.001, rt / 3600000.0);

        String[] lines = new String[]{
                "Varrock Agility+ (xploitHQ)",
                "Runtime: " + formatTime(rt),
                "Agility: " + getSkills().getDynamic(Skill.AGILITY),
                "XP gained: " + df1.format(xpGained) + "  (" + df1.format((int) (xpGained / hrs)) + "/hr)",
                "Laps: " + lapsDone + "  (" + df2.format(lapsDone / hrs) + "/hr)",
                "Marks: " + marksCount + "  (" + df2.format(marksCount / hrs) + "/hr)",
                "Run: " + getSettings().getRunEnergy() + "%",
                (USE_STAMINA ? "Stamina: ON" : "Stamina: OFF") + " | " +
                        (USE_FOOD ? ("Food@" + EAT_HP) : "Food: OFF"),
        };

        int w = 250, h = 18 * lines.length + 14;
        int x = 10, y = 30;
        g.setColor(new Color(20, 20, 20, 170));
        g.fillRoundRect(x, y, w, h, 12, 12);
        g.setColor(Color.WHITE);
        g.drawRoundRect(x, y, w, h, 12, 12);

        int ty = y + 22;
        for (String s : lines) {
            g.drawString(s, x + 10, ty);
            ty += 18;
        }
    }

    // ============================= UTILS =============================
    private boolean nameAny(String name, String[] candidates) {
        if (name == null || candidates == null) return false;
        for (String c : candidates) if (equalsIgnoreCase(name, c)) return true;
        return false;
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private boolean containsIgnoreCase(String hay, String needle) {
        return hay != null && needle != null && hay.toLowerCase().contains(needle.toLowerCase());
    }

    private int rand(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private void logf(String fmt, Object... args) {
        if (!LOGGING) return;
        long now = System.currentTimeMillis();
        log("[" + (now - startTime) / 1000 + "s] " + String.format(fmt, args));
    }

    private String formatTime(long ms) {
        long s = ms / 1000;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
