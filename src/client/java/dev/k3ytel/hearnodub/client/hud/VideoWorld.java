package dev.k3ytel.hearnodub.client.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.k3ytel.hearnodub.client.config.BubbleTarget;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.config.VideoMode;
import dev.k3ytel.hearnodub.client.video.VideoCapture;
import dev.k3ytel.hearnodub.client.video.VideoTexture;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import dev.k3ytel.hearnodub.client.video.TvScreen;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The video as a screen in the 3D world:
 * <ul>
 *   <li>FLOATING: in front of you, gliding after your view</li>
 *   <li>FLOATING_SIDE: the same, but off to the side of your view so it doesn't cover the middle</li>
 *   <li>TV: fixed in the world (see TvScreen)</li>
 *   <li>ABOVE_MOB: above the mob you look at, or above you (same target setting as speech bubbles)</li>
 * </ul>
 * Turns to face you the same way 3D subtitles do, and always shows at full brightness.
 */
public final class VideoWorld {
	private VideoWorld() {}

	private static final int FULL_BRIGHT = 15728880;
	/** Black frame around the picture, in blocks. */
	private static final float FRAME = 0.04f;

	// FLOATING state
	private static Vec3 anchor;
	private static ClientLevel anchorLevel;
	private static long lastFrameNanos;

	// ABOVE_MOB state (for the "looked-at mob" target: kept until it's gone or far away)
	private static Entity mobTarget;

	/**
	 * Where attached subtitles go this frame: under the screen (above it for a mob screen),
	 * turned the same way as the screen.
	 */
	public record Attach(Vec3 pos, Quaternionf rotation, float screenWidth, boolean above) {}

	private static Attach attach;
	private static Vec3 screenCenter, screenRight, screenUp;
	private static float screenHalfWidth, screenHalfHeight;
	private static long screenSeenAt;

	/** Attach point for subtitles, or null when no screen was drawn this frame. */
	public static Attach attach() {
		return attach;
	}

	/** Middle of the last drawn screen, or null if none was drawn in the last second. */
	public static Vec3 screenCenter() {
		return System.currentTimeMillis() - screenSeenAt < 1000 ? screenCenter : null;
	}

	/**
	 * Distance from a point to the nearest point of the last drawn screen (not its middle: a
	 * big TV is "close" along its whole width), or -1 if there's no screen.
	 */
	public static double distanceToScreen(Vec3 p) {
		Vec3 c = screenCenter();
		if (c == null || screenRight == null) return -1;
		Vec3 d = p.subtract(c);
		double along = Math.max(-screenHalfWidth, Math.min(screenHalfWidth, d.dot(screenRight)));
		double upDown = Math.max(-screenHalfHeight, Math.min(screenHalfHeight, d.dot(screenUp)));
		Vec3 nearest = c.add(screenRight.scale(along)).add(screenUp.scale(upDown));
		return p.distanceTo(nearest);
	}

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(VideoWorld::collect);
	}

	private static void collect(LevelRenderContext ctx) {
		attach = null;
		HearNoDubConfig cfg = HearNoDubConfig.get();
		Minecraft mc = Minecraft.getInstance();
		if (!cfg.videoEnabled || !cfg.videoMode.inWorld() || mc.player == null || mc.level == null) {
			anchor = null;
			return;
		}
		CameraRenderState cam = ctx.levelState().cameraRenderState;
		if (cam == null || !cam.initialized || cam.pos == null) return;
		if (VideoTexture.texture() == null || VideoTexture.width() <= 0) return;
		if (!VideoCapture.showable(VideoCapture.get().state())) return;

		float aspect = VideoTexture.height() / (float) VideoTexture.width();
		if (cfg.videoMode == VideoMode.FLOATING || cfg.videoMode == VideoMode.FLOATING_SIDE) {
			updateAnchor(cfg, mc.level, cam, cfg.videoMode == VideoMode.FLOATING_SIDE);
			float w = cfg.videoWorldWidth;
			drawScreen(ctx, cfg, cam, anchor, w, w * aspect, false, null);
		} else if (cfg.videoMode == VideoMode.TV) {
			TvScreen.Tv tv = TvScreen.current();
			if (tv == null) return;
			float w = tv.width(aspect), h = w * aspect;
			Vec3 n = tv.normal();
			Vec3 center = tv.anchorPos().add(n.scale(0.02)); // a hair off the wall
			if (tv.anchor == TvScreen.Anchor.STAND) center = center.add(0, h / 2 + FRAME, 0);
			if (tv.anchor == TvScreen.Anchor.HANG) center = center.add(0, -h / 2 - FRAME, 0);
			Vector3f back = new Vector3f((float) n.x, 0, (float) n.z);
			Vector3f up = new Vector3f(0, 1, 0);
			Vector3f right = new Vector3f(up).cross(back).normalize();
			Quaternionf rot = new Quaternionf().setFromNormalized(new Matrix3f(right, up, back));
			drawScreen(ctx, cfg, cam, center, w, h, false, rot);
		} else {
			Entity target = target(mc, cfg, cam);
			if (target == null) return;
			float w = cfg.videoMobWidth, h = w * aspect;
			double lift = target.getBbHeight() + cfg.videoMobLift + (target.shouldShowName() ? 0.3 : 0);
			Vec3 pos = target.getPosition(cam.cameraEntityPartialTicks).add(0, lift, 0);
			drawScreen(ctx, cfg, cam, pos, w, h, true, null);
		}
	}

	/**
	 * Draws the picture as a flat quad, w × h blocks, centred on pos (or standing on it when
	 * bottomAnchored), with a thin black frame behind it. Turned to face you, unless a fixed
	 * rotation is given (the TV).
	 */
	private static void drawScreen(LevelRenderContext ctx, HearNoDubConfig cfg, CameraRenderState cam, Vec3 pos,
			float w, float h, boolean bottomAnchored, Quaternionf fixedRotation) {
		SubmitNodeCollector out = ctx.submitNodeCollector();
		PoseStack pose = ctx.poseStack();
		Quaternionf rot = fixedRotation != null ? fixedRotation : WorldSubtitles.facing(cam, cfg, pos);
		Vector3f upDir = rot.transform(new Vector3f(0, 1, 0));
		Vector3f rightDir = rot.transform(new Vector3f(1, 0, 0));
		Vec3 up = new Vec3(upDir.x(), upDir.y(), upDir.z());
		Vec3 center = bottomAnchored ? pos.add(up.scale(h / 2f + FRAME)) : pos;
		screenCenter = center;
		screenRight = new Vec3(rightDir.x(), rightDir.y(), rightDir.z());
		screenUp = up;
		screenHalfWidth = w / 2f;
		screenHalfHeight = h / 2f;
		screenSeenAt = System.currentTimeMillis();
		attach = bottomAnchored
				? new Attach(center.add(up.scale(h / 2f + FRAME)), rot, w, true)
				: new Attach(center.add(up.scale(-h / 2f - FRAME)), rot, w, false);

		pose.pushPose();
		pose.translate(pos.x - cam.pos.x, pos.y - cam.pos.y, pos.z - cam.pos.z);
		pose.rotate(rot);
		if (bottomAnchored) pose.translate(0f, h / 2f + FRAME, 0f);

		float x0 = -w / 2f, x1 = w / 2f, y0 = -h / 2f, y1 = h / 2f;
		Font.DisplayMode mode = cfg.worldSeeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.POLYGON_OFFSET;
		// Black frame. Skipped when drawing through blocks: without the depth test it can end up
		// drawn on top of the picture (that's what made the screen black).
		if (!cfg.worldSeeThrough) {
			// Text backgrounds are drawn in "text space" (y down), so flip for the frame
			pose.pushPose();
			pose.scale(1f, -1f, 1f);
			int frameColor = Math.round(255 * cfg.videoOpacity / 100f) << 24;
			out.submitTextBackground(pose, x0 - FRAME, -y1 - FRAME, x1 + FRAME, -y0 + FRAME, frameColor, mode, FULL_BRIGHT);
			pose.popPose();
		}

		int alpha = Math.round(255 * cfg.videoOpacity / 100f);
		int color = (alpha << 24) | 0xFFFFFF;
		boolean opaque = alpha >= 250;
		if (opaque && !cfg.worldSeeThrough) {
			// A solid copy behind the picture: it fills the depth buffer, so clouds and other
			// see-through things behind the screen don't show through it
			out.submitCustomGeometry(pose, RenderTypes.entitySolid(VideoTexture.ID), (p, vc) -> {
				// a little behind the bright picture, so the two never fight (front side only:
				// a back copy would sit in front of the picture if this pipeline doesn't cull)
				float d = 0.012f;
				solidVertex(vc, p, x0, y1, -d, 0f, 0f, 1f);
				solidVertex(vc, p, x0, y0, -d, 0f, 1f, 1f);
				solidVertex(vc, p, x1, y0, -d, 1f, 1f, 1f);
				solidVertex(vc, p, x1, y1, -d, 1f, 0f, 1f);
			});
		}

		if (cfg.videoGlow > 0) drawGlow(ctx, cfg, cam, pose, center, up, rot, w, h, fixedRotation != null);

		RenderType type = cfg.worldSeeThrough ? RenderTypes.textSeeThrough(VideoTexture.ID) : RenderTypes.text(VideoTexture.ID);
		float z = 0.002f; // just in front of the frame
		// A later order is drawn after the frame and other text, so the picture ends up on top
		out.order(1).submitCustomGeometry(pose, type, (p, vc) -> {
			// front (seen from +Z, where the viewer is) ...
			vertex(vc, p, x0, y1, z, 0f, 0f, color);
			vertex(vc, p, x0, y0, z, 0f, 1f, color);
			vertex(vc, p, x1, y0, z, 1f, 1f, color);
			vertex(vc, p, x1, y1, z, 1f, 0f, color);
			// ... and back, so it doesn't vanish when seen from behind
			vertex(vc, p, x1, y1, -z, 1f, 0f, color);
			vertex(vc, p, x1, y0, -z, 1f, 1f, color);
			vertex(vc, p, x0, y0, -z, 0f, 1f, color);
			vertex(vc, p, x0, y1, -z, 0f, 0f, color);
		});
		pose.popPose();
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float u, float v, int color) {
		vc.addVertex(p, x, y, z).setColor(color).setUv(u, v).setLight(FULL_BRIGHT);
	}

	private static void solidVertex(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float u, float v, float nz) {
		vc.addVertex(p, x, y, z).setColor(0xFFFFFFFF).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(FULL_BRIGHT).setNormal(p, 0f, 0f, nz);
	}

	// ---------------------------------------------------------------- glow

	/** The glow colour, eased toward the picture's average colour so it doesn't flicker. */
	private static float glowR, glowG, glowB;
	private static long glowAt;

	/** Lit block faces around the screen (cached inside). */
	private static final dev.k3ytel.hearnodub.client.render.BlockGlow GLOW = new dev.k3ytel.hearnodub.client.render.BlockGlow();

	/**
	 * The screen lights up the blocks around it in the colours of the picture, like light
	 * bouncing off them (see {@link dev.k3ytel.hearnodub.client.render.BlockGlow}).
	 */
	private static void drawGlow(LevelRenderContext ctx, HearNoDubConfig cfg, CameraRenderState cam, PoseStack pose,
			Vec3 center, Vec3 up, Quaternionf rot, float w, float h, boolean tv) {
		int avg = VideoCapture.get().averageColor();
		long now = System.nanoTime();
		float dt = glowAt == 0 ? 1f : Math.min(1f, (now - glowAt) / 1e9f);
		glowAt = now;
		float k = 1 - (float) Math.exp(-dt / 0.25f);
		glowR += (((avg >> 16) & 255) - glowR) * k;
		glowG += (((avg >> 8) & 255) - glowG) * k;
		glowB += ((avg & 255) - glowB) * k;
		// Most frames are fairly dark on average, so boost a lot
		float strength = cfg.videoGlow / 100f * cfg.videoOpacity / 100f * 3.0f;
		float r = glowR * strength / 255f, g = glowG * strength / 255f, b = glowB * strength / 255f;

		Vector3f backDir = rot.transform(new Vector3f(0, 0, 1));
		Vec3 facing = new Vec3(backDir.x(), backDir.y(), backDir.z());
		// Light comes from a point just in front of the screen (a TV only lights what's in front of it)
		Vec3 light = tv ? center.add(facing.scale(0.4)) : center;
		float reach = Math.max(w, h) * 0.9f + 3f;
		GLOW.draw(ctx, cam, light, tv ? facing : null, reach, r, g, b, tv ? 1000 : 150);
	}

	// ---------------------------------------------------------------- floating

	private static void updateAnchor(HearNoDubConfig cfg, ClientLevel level, CameraRenderState cam, boolean side) {
		Vec3 look = WorldSubtitles.cameraLook(cam);
		if (side) look = turned(look, cfg.videoSideAngle, cfg.videoSideTilt);
		Vec3 target = cam.pos.add(look.scale(cfg.videoWorldDistance)).add(0, cfg.videoWorldHeight, 0);
		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0 : Math.min(0.25, (now - lastFrameNanos) / 1e9);
		lastFrameNanos = now;
		if (anchor == null || anchorLevel != level || anchor.distanceTo(target) > cfg.videoWorldDistance * 4
				|| cfg.videoFollowDelay <= 0.01f) {
			anchor = target;
			anchorLevel = level;
			return;
		}
		double tau = cfg.videoFollowDelay / 3.0;
		anchor = anchor.lerp(target, 1 - Math.exp(-dt / tau));
	}

	/** The view direction turned right (degrees, negative = left) and up (negative = down). */
	private static Vec3 turned(Vec3 look, int rightDeg, int upDeg) {
		Vec3 worldUp = new Vec3(0, 1, 0);
		Vec3 right = look.cross(worldUp);
		if (right.lengthSqr() < 1e-6) return look; // looking straight up or down
		right = right.normalize();
		double a = Math.toRadians(rightDeg);
		Vec3 dir = look.scale(Math.cos(a)).add(right.scale(Math.sin(a))).normalize();
		Vec3 up = right.cross(dir).normalize();
		double b = Math.toRadians(upDeg);
		return dir.scale(Math.cos(b)).add(up.scale(Math.sin(b))).normalize();
	}

	// ---------------------------------------------------------------- above a mob

	/** Same choice as the subtitles' speech bubble: Me, Auto (re-checked every frame), or Looked-at (kept). */
	private static Entity target(Minecraft mc, HearNoDubConfig cfg, CameraRenderState cam) {
		if (cfg.bubbleTarget == BubbleTarget.SELF) return mc.player;
		if (cfg.bubbleTarget == BubbleTarget.AUTO) {
			Entity mob = WorldSubtitles.lookedAtMob(mc, cfg, cam);
			return mob != null ? mob : mc.player;
		}
		if (mobTarget == null || !mobTarget.isAlive() || mobTarget.level() != mc.level
				|| mobTarget.position().distanceTo(cam.pos) > cfg.bubbleRange * 1.5) {
			Entity mob = WorldSubtitles.lookedAtMob(mc, cfg, cam);
			mobTarget = mob != null ? mob : mc.player;
		} else if (mobTarget == mc.player) {
			Entity mob = WorldSubtitles.lookedAtMob(mc, cfg, cam);
			if (mob != null) mobTarget = mob; // look at a mob to give it the screen
		}
		return mobTarget;
	}
}
