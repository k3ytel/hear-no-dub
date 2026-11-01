package dev.k3ytel.hearnodub.client.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.k3ytel.hearnodub.client.config.Billboard;
import dev.k3ytel.hearnodub.client.config.BubbleTarget;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.config.WorldAnchor;
import dev.k3ytel.hearnodub.client.config.WorldMode;
import dev.k3ytel.hearnodub.client.render.SubStyle;
import dev.k3ytel.hearnodub.client.render.SubText;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import dev.k3ytel.hearnodub.client.subs.SubtitleTrack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Random;

/**
 * 3D subtitles, facing you like name tags:
 * <ul>
 *   <li>FLOATING: in front of you (lazily follows your view) or fixed where you placed it</li>
 *   <li>POPIN: each line pops up at a random spot in your view and stays there relative to your camera</li>
 *   <li>BUBBLE: above the mob you're looking at, or above your own head</li>
 * </ul>
 */
public final class WorldSubtitles {
	private WorldSubtitles() {}

	private static final int FULL_BRIGHT = 15728880;
	/** Vanilla text displays use this many blocks per text pixel at scale 1. */
	private static final float PIXEL = 0.025f;
	private static final int WRAP_PX = 220;
	private static final Random RANDOM = new Random();

	// FLOATING state
	private static Vec3 anchor;
	private static ClientLevel anchorLevel;
	private static boolean placeRequested;
	private static long lastFrameNanos;

	// POPIN state: angle offsets from the view direction, per subtitle group
	private static long popKey = Long.MIN_VALUE;
	private static float popYaw, popPitch;

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(WorldSubtitles::collect);
	}

	/** Puts FIXED-mode subtitles in front of you (and re-centres FOLLOW mode instantly). */
	public static void placeHere() {
		placeRequested = true;
	}

	private static void collect(LevelRenderContext ctx) {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		Minecraft mc = Minecraft.getInstance();
		if (!cfg.enabled || mc.player == null || mc.level == null) return;
		WorldMode mode = effectiveMode(mc, cfg);
		if (mode == WorldMode.NONE) return;
		if (mc.gui.hud.isHidden()) return;

		CameraRenderState cam = ctx.levelState().cameraRenderState;
		if (cam == null || !cam.initialized || cam.pos == null) return;

		SubtitleManager m = SubtitleManager.get();
		List<SubtitleTrack.Cue> cues = m.activeCues();
		long groupKey = cues.isEmpty() ? Long.MIN_VALUE : cues.stream().mapToLong(SubtitleTrack.Cue::startMs).min().getAsLong();
		if (mode == WorldMode.FLOATING) updateAnchor(cfg, mc.level, cam, groupKey);
		if (cues.isEmpty()) return;

		SubStyle style = SubStyle.resolve(cfg, true);
		SubtitleTrack.Cue next = mode == WorldMode.FLOATING ? m.nextCue() : null;
		SubText.Block block = SubText.build(mc.font, cues, next, m.subtitleTimeMs(), cfg, WRAP_PX, style.textColor());
		if (block.isEmpty()) return;

		switch (mode) {
			case FLOATING -> {
				if (anchor != null) draw(ctx, mc.font, block, style, cfg, anchor, cam, cfg.worldScale, false);
			}
			case POPIN -> {
				if (block.key() != popKey) {
					popKey = block.key();
					pickSpot(cfg);
				}
				Vec3 dir = Vec3.directionFromRotation(cam.xRot + popPitch, cam.yRot + popYaw);
				Vec3 pos = cam.pos.add(dir.scale(cfg.popinDistance));
				draw(ctx, mc.font, block, style, cfg, pos, cam, cfg.popinScale, false);
			}
			case BUBBLE -> {
				Entity speaker = bubbleTarget(mc, cfg, cam, block.key());
				if (speaker == null) return;
				float pt = cam.cameraEntityPartialTicks;
				double lift = speaker.getBbHeight() + 0.55 + (speaker.shouldShowName() ? 0.3 : 0);
				Vec3 pos = speaker.getPosition(pt).add(0, lift, 0);
				draw(ctx, mc.font, block, style, cfg, pos, cam, cfg.worldScale, true);
			}
			default -> { }
		}
	}

	/** The 3D mode to use right now: speech bubbles in third person if that toggle is on. */
	private static WorldMode effectiveMode(Minecraft mc, HearNoDubConfig cfg) {
		if (cfg.thirdPersonBubble && !mc.options.getCameraType().isFirstPerson()) return WorldMode.BUBBLE;
		return cfg.worldMode;
	}

	// ---------------------------------------------------------------- drawing

	private static void draw(LevelRenderContext ctx, Font font, SubText.Block block, SubStyle style, HearNoDubConfig cfg,
			Vec3 pos, CameraRenderState cam, float scale, boolean bubble) {
		SubmitNodeCollector out = ctx.submitNodeCollector();
		PoseStack pose = ctx.poseStack();
		Font.DisplayMode mode = cfg.worldSeeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.POLYGON_OFFSET;
		float a = block.alpha();
		int w = block.width(), h = block.height();

		pose.pushPose();
		// The pose stack is relative to the camera position
		pose.translate(pos.x - cam.pos.x, pos.y - cam.pos.y, pos.z - cam.pos.z);
		pose.rotate(facing(cam, cfg, pos));
		float s = PIXEL * scale * block.scale();
		pose.scale(s, -s, s);
		// Bubbles sit on top of their anchor; everything else is centred on it
		pose.translate(-w / 2f, bubble ? -h - 8f : -h / 2f, 0f);

		boolean box = style.box() || bubble;
		if (box) {
			int boxColor = style.box() ? style.boxColor()
					: SubStyle.fade(0xFF000000, Math.max(0.35f, cfg.worldBackgroundOpacity / 100f));
			int c = SubStyle.fade(boxColor, a);
			out.submitTextBackground(pose, -3f, -2f, w + 3f, h + 2f, c, mode, FULL_BRIGHT);
			if (bubble) {
				// little tail pointing down at the speaker
				out.submitTextBackground(pose, w / 2f - 3f, h + 2f, w / 2f + 3f, h + 4f, c, mode, FULL_BRIGHT);
				out.submitTextBackground(pose, w / 2f - 1.5f, h + 4f, w / 2f + 1.5f, h + 6f, c, mode, FULL_BRIGHT);
			}
		}

		int outline = 0;
		if (style.outline()) outline = SubStyle.fade(style.outlineColor(), a);
		else if (style.glow()) outline = SubStyle.fade(style.glowColor(), 0.55f * a);

		int y = 0;
		for (SubText.Line line : block.lines()) {
			float la = line.preview() ? a * 0.6f : a;
			float x = (w - line.width()) / 2f;
			out.submitText(pose, x, y, line.colored(), style.shadow() && !style.outline(), mode, FULL_BRIGHT,
					SubStyle.fade(0xFFFFFFFF, la), 0, line.preview() ? 0 : outline);
			y += font.lineHeight + SubText.LINE_GAP;
		}
		pose.popPose();
	}

	/**
	 * How the text plane is turned. In text space +X is "right", +Y is "up" and +Z points back
	 * toward the viewer (same convention as the camera rotation).
	 * <ul>
	 *   <li>FACE_CAMERA: the camera's own rotation (flat to the screen, copies any tilt)</li>
	 *   <li>UPRIGHT: flat to the screen, but "up" forced to world up</li>
	 *   <li>TOWARD_ME: points straight at your eyes from wherever it is</li>
	 *   <li>TOWARD_ME_VERTICAL: same, but only turns around the vertical axis</li>
	 * </ul>
	 */
	private static Quaternionf facing(CameraRenderState cam, HearNoDubConfig cfg, Vec3 pos) {
		switch (cfg.billboard) {
			case FACE_CAMERA -> {
				return cam.orientation;
			}
			case UPRIGHT -> {
				Vector3f right = cam.orientation.transform(new Vector3f(1, 0, 0));
				right.y = 0;
				if (right.lengthSquared() < 1e-6f) return cam.orientation; // looking straight up/down
				right.normalize();
				Vector3f up = new Vector3f(0, 1, 0);
				return basis(right, up, new Vector3f(right).cross(up));
			}
			default -> {
				// "back" points from the text to your eyes
				Vector3f back = new Vector3f((float) (cam.pos.x - pos.x), (float) (cam.pos.y - pos.y), (float) (cam.pos.z - pos.z));
				if (cfg.billboard == Billboard.TOWARD_ME_VERTICAL) back.y = 0;
				if (back.lengthSquared() < 1e-6f) return cam.orientation;
				back.normalize();
				Vector3f worldUp = new Vector3f(0, 1, 0);
				Vector3f right = new Vector3f(worldUp).cross(back);
				if (right.lengthSquared() < 1e-6f) return cam.orientation; // text straight above/below you
				right.normalize();
				Vector3f up = new Vector3f(back).cross(right);
				return basis(right, up, back);
			}
		}
	}

	private static Quaternionf basis(Vector3f right, Vector3f up, Vector3f back) {
		return new Quaternionf().setFromNormalized(new Matrix3f(right, up, back));
	}

	// ---------------------------------------------------------------- floating

	private static long anchorKey = Long.MIN_VALUE;

	private static void updateAnchor(HearNoDubConfig cfg, ClientLevel level, CameraRenderState cam, long groupKey) {
		Vec3 look = Vec3.directionFromRotation(cam.xRot, cam.yRot);
		Vec3 target = cam.pos.add(look.scale(cfg.worldDistance)).add(0, cfg.worldHeight, 0);

		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0 : Math.min(0.25, (now - lastFrameNanos) / 1e9);
		lastFrameNanos = now;

		boolean reset = anchor == null || anchorLevel != level || placeRequested;
		placeRequested = false;
		anchorLevel = level;

		switch (cfg.worldAnchor) {
			case FIXED -> {
				if (reset) anchor = target;
			}
			case PER_LINE -> {
				// A new line spawns where you're looking now, then stays there in the world
				if (groupKey != Long.MIN_VALUE && (groupKey != anchorKey || reset)) {
					anchorKey = groupKey;
					if (cfg.perLineRandom) {
						pickSpot(cfg);
						Vec3 dir = Vec3.directionFromRotation(cam.xRot + popPitch, cam.yRot + popYaw);
						anchor = cam.pos.add(dir.scale(cfg.worldDistance)).add(0, cfg.worldHeight, 0);
					} else {
						anchor = target;
					}
				}
			}
			case FOLLOW -> {
				// Snap if far away (teleport, respawn); otherwise glide toward the spot in front of you
				if (reset || anchor.distanceTo(target) > cfg.worldDistance * 4 || cfg.worldFollowDelay <= 0.01f) {
					anchor = target;
					return;
				}
				// Exponential smoothing: covers ~95% of the gap in worldFollowDelay seconds
				double tau = cfg.worldFollowDelay / 3.0;
				double alpha = 1 - Math.exp(-dt / tau);
				anchor = anchor.lerp(target, alpha);
			}
		}
	}

	// ---------------------------------------------------------------- pop-in

	/** A random spot in view: not on the crosshair, and away from the previous spot. */
	static void pickSpot(HearNoDubConfig cfg) {
		float maxX = cfg.popinSpreadX, maxY = cfg.popinSpreadY;
		float prevYaw = popYaw, prevPitch = popPitch;
		float yaw = 0, pitch = 0;
		for (int i = 0; i < 30; i++) {
			yaw = (RANDOM.nextFloat() * 2 - 1) * maxX;
			pitch = (RANDOM.nextFloat() * 2 - 1) * maxY;
			boolean onCrosshair = Math.abs(yaw) < Math.min(9, maxX * 0.4f) && Math.abs(pitch) < Math.min(6, maxY * 0.6f + 1);
			double fromPrev = Math.hypot(yaw - prevYaw, pitch - prevPitch);
			if (!onCrosshair && fromPrev >= Math.max(maxX, maxY) * 0.5) break;
		}
		popYaw = yaw;
		popPitch = pitch;
	}

	// ---------------------------------------------------------------- bubble

	private static long bubbleKey = Long.MIN_VALUE;
	private static Entity bubbleEntity;

	/**
	 * SELF: always you. AUTO: whoever you're looking at right now (re-checked every frame).
	 * LOOKED_AT: picked when a line starts and kept for that whole line, so a glance away
	 * doesn't move it. Without a mob in view, it's you talking.
	 */
	private static Entity bubbleTarget(Minecraft mc, HearNoDubConfig cfg, CameraRenderState cam, long key) {
		if (cfg.bubbleTarget == BubbleTarget.SELF) return mc.player;
		if (cfg.bubbleTarget == BubbleTarget.AUTO) {
			Entity mob = lookedAtMob(mc, cfg, cam);
			return mob != null ? mob : mc.player;
		}
		if (key != bubbleKey || bubbleEntity == null || !bubbleEntity.isAlive() || bubbleEntity.level() != mc.level
				|| bubbleEntity.position().distanceTo(cam.pos) > cfg.bubbleRange * 1.5) {
			bubbleKey = key;
			Entity mob = lookedAtMob(mc, cfg, cam);
			bubbleEntity = mob != null ? mob : mc.player;
		}
		return bubbleEntity;
	}

	/** The living entity closest to the centre of your view, within range and ~20°. */
	private static Entity lookedAtMob(Minecraft mc, HearNoDubConfig cfg, CameraRenderState cam) {
		if (mc.crosshairPickEntity instanceof LivingEntity le && le != mc.player && le.isAlive()) return le;
		Vec3 look = Vec3.directionFromRotation(cam.xRot, cam.yRot);
		double best = Math.cos(Math.toRadians(20));
		Entity bestEntity = null;
		double range = cfg.bubbleRange;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof LivingEntity) || e == mc.player || !e.isAlive() || e.isInvisible()) continue;
			Vec3 to = e.getEyePosition(cam.cameraEntityPartialTicks).subtract(cam.pos);
			double dist = to.length();
			if (dist > range || dist < 0.5) continue;
			double dot = to.scale(1 / dist).dot(look);
			if (dot > best) {
				best = dot;
				bestEntity = e;
			}
		}
		return bestEntity;
	}
}
