package dev.k3ytel.hearnodub.client.media;

import dev.k3ytel.hearnodub.client.config.DangerLevel;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Danger pause: the video pauses by itself when you could actually die, and plays again once
 * you've been safe for a few seconds. Only resumes what it paused itself.
 * <ul>
 *   <li>Relaxed: only your health. You're below the health limit and still getting hurt, or
 *       you lost a big chunk at once. Berry bushes and small falls never count.</li>
 *   <li>Careful: also a hostile mob moving toward you at your level, or a creeper with its
 *       fuse lit. Ignored while you're fighting on purpose (you hit a mob recently).</li>
 * </ul>
 * A snooze key turns it off for 10 minutes.
 */
public final class DangerPause {
	private DangerPause() {}

	private static final long BURST_WINDOW_MS = 3000;
	private static final long HURT_RECENT_MS = 3000;
	private static final long FIGHTING_MS = 10_000;
	private static final long SNOOZE_MS = 10 * 60_000;
	/** How far a mob must move toward you within APPROACH_MS to count as coming for you. */
	private static final double APPROACH_BLOCKS = 1.5;
	private static final long APPROACH_MS = 1500;
	private static final double MAX_HEIGHT_DIFF = 3;

	private static boolean pausedByUs;
	private static long safeSince;
	private static long snoozedUntil;
	private static long lastAttackAt;
	private static long lastHurtAt;
	private static float lastHealth = -1;
	private static final Deque<long[]> damage = new ArrayDeque<>(); // {time, damage × 100}
	private static final Map<Integer, Deque<Object[]>> mobTrail = new HashMap<>(); // id -> {time, pos}

	public static void register() {
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide()) lastAttackAt = System.currentTimeMillis();
			return InteractionResult.PASS;
		});
	}

	/** The snooze key: no danger pauses for 10 minutes, or back on if already snoozed. */
	public static void toggleSnooze(Minecraft mc) {
		long now = System.currentTimeMillis();
		boolean snooze = now >= snoozedUntil;
		snoozedUntil = snooze ? now + SNOOZE_MS : 0;
		if (snooze) pausedByUs = false;
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.translatable(snooze ? "hear-no-dub.danger.snoozed" : "hear-no-dub.danger.unsnoozed")
					.withStyle(snooze ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
		}
	}

	public static void tick(Minecraft mc) {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		LocalPlayer player = mc.player;
		long now = System.currentTimeMillis();
		if (player == null || mc.level == null) {
			lastHealth = -1;
			damage.clear();
			mobTrail.clear();
			return;
		}
		trackHealth(player, now);
		if (cfg.dangerLevel == DangerLevel.CAREFUL) trackMobs(mc, player, cfg, now);
		else mobTrail.clear();

		MediaBridge.Session s = SubtitleManager.get().session();
		if (!cfg.dangerPause || s == null || now < snoozedUntil || player.isCreative() || player.isSpectator()) {
			pausedByUs = false;
			return;
		}
		Component reason = danger(mc, player, cfg, now);
		if (reason != null) {
			safeSince = now;
			if (s.isPlaying() && !pausedByUs && MediaBridge.get().sendCommand("pause", s.app)) {
				pausedByUs = true;
				player.sendOverlayMessage(Component.translatable("hear-no-dub.danger.paused", reason).withStyle(ChatFormatting.RED));
			}
			return;
		}
		if (!pausedByUs) return;
		if (s.isPlaying()) {
			pausedByUs = false; // you pressed play yourself
			return;
		}
		if (now - safeSince >= cfg.dangerResumeSec * 1000L) {
			pausedByUs = false;
			if (mc.gui.screen() == null && MediaBridge.get().sendCommand("play", s.app)) {
				player.sendOverlayMessage(Component.translatable("hear-no-dub.danger.resumed").withStyle(ChatFormatting.GREEN));
			}
		}
	}

	/** You pressed play/pause yourself: don't fight you over it. */
	public static void userTookOver() {
		pausedByUs = false;
	}

	// ---------------------------------------------------------------- what counts as danger

	private static Component danger(Minecraft mc, LocalPlayer player, HearNoDubConfig cfg, long now) {
		float max = Math.max(1, player.getMaxHealth());
		float health = player.getHealth();
		boolean hurtRecently = now - lastHurtAt < HURT_RECENT_MS;
		if (hurtRecently && health <= max * cfg.dangerHealthPercent / 100f) {
			return Component.translatable("hear-no-dub.danger.low_health");
		}
		if (recentDamage(now) >= cfg.dangerBurstHearts * 2f) {
			return Component.translatable("hear-no-dub.danger.big_hit");
		}
		if (cfg.dangerLevel != DangerLevel.CAREFUL) return null;
		if (now - lastAttackAt < FIGHTING_MS) return null; // fighting on purpose: only health matters

		double range = cfg.dangerRange;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof Enemy) || !e.isAlive() || e == player) continue;
			double d = e.distanceTo(player);
			if (d > range || Math.abs(e.getY() - player.getY()) > MAX_HEIGHT_DIFF) continue;
			if (e instanceof Creeper creeper && creeper.getSwelling(1f) > 0 && d <= 8) return e.getDisplayName();
			if (comingForYou(e, player, now) && player.hasLineOfSight(e)) return e.getDisplayName();
		}
		return null;
	}

	private static void trackHealth(LocalPlayer player, long now) {
		float h = player.getHealth();
		if (lastHealth >= 0 && h < lastHealth - 0.01f) {
			damage.addLast(new long[]{now, Math.round((lastHealth - h) * 100)});
			lastHurtAt = now;
		}
		lastHealth = h;
		while (!damage.isEmpty() && now - damage.peekFirst()[0] > BURST_WINDOW_MS) damage.removeFirst();
	}

	/** Health lost in the last few seconds. */
	private static float recentDamage(long now) {
		long sum = 0;
		for (long[] d : damage) if (now - d[0] <= BURST_WINDOW_MS) sum += d[1];
		return sum / 100f;
	}

	// ---------------------------------------------------------------- mobs moving toward you

	private static void trackMobs(Minecraft mc, LocalPlayer player, HearNoDubConfig cfg, long now) {
		double range = cfg.dangerRange + 4;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof Enemy) || !e.isAlive() || e.distanceTo(player) > range) continue;
			Deque<Object[]> trail = mobTrail.computeIfAbsent(e.getId(), k -> new ArrayDeque<>());
			trail.addLast(new Object[]{now, e.position()});
			while (trail.size() > 1 && now - (long) trail.peekFirst()[0] > APPROACH_MS) trail.removeFirst();
		}
		// forget mobs we haven't seen for a while
		for (Iterator<Deque<Object[]>> it = mobTrail.values().iterator(); it.hasNext(); ) {
			Deque<Object[]> t = it.next();
			if (t.isEmpty() || now - (long) t.peekLast()[0] > 2000) it.remove();
		}
	}

	/**
	 * The mob itself moved toward you: its own movement over the last ~1.5 s, measured along
	 * the direction to you. You walking up to a mob that stands still doesn't count, and
	 * neither do mobs stuck behind glass or in a pit.
	 */
	private static boolean comingForYou(Entity e, LocalPlayer player, long now) {
		Deque<Object[]> trail = mobTrail.get(e.getId());
		if (trail == null || trail.size() < 2) return false;
		Object[] first = trail.peekFirst();
		if (now - (long) first[0] < APPROACH_MS * 0.6) return false; // not watched long enough yet
		Vec3 from = (Vec3) first[1];
		Vec3 moved = e.position().subtract(from);
		Vec3 toYou = player.position().subtract(e.position());
		double len = Math.sqrt(toYou.x * toYou.x + toYou.z * toYou.z);
		if (len < 1e-3) return true;
		double towardYou = (moved.x * toYou.x + moved.z * toYou.z) / len;
		return towardYou >= APPROACH_BLOCKS;
	}
}
