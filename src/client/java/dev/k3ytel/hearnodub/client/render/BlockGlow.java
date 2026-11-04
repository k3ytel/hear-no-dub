package dev.k3ytel.hearnodub.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.k3ytel.hearnodub.client.video.GlowTexture;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Light from a point (a video screen, glowing letters) bouncing off the blocks around it:
 * every block face near the light that faces it gets a soft coat of coloured light, brighter
 * when it's close and turned toward the light. Nothing glows in empty air, and faces hidden
 * behind other blocks stay dark. Each light keeps its own instance (the faces are cached).
 */
public final class BlockGlow {
	private static final int FULL_BRIGHT = 15728880;

	private record LitFace(Vec3[] corners, float[] light) {}

	private List<LitFace> faces = List.of();
	private long facesAt;
	private Vec3 facesFor;
	private float facesReach;

	/**
	 * @param light        where the light comes from
	 * @param onlyInFront  only light what's in front of this direction (a TV), or null for all around
	 * @param reach        how far the light goes, in blocks
	 * @param r            colour, 0..1 or more (more = brighter)
	 * @param refreshMs    how often to look for faces again (blocks rarely change; moving lights need it often)
	 */
	public void draw(LevelRenderContext ctx, CameraRenderState cam, Vec3 light, Vec3 onlyInFront, float reach,
			float r, float g, float b, long refreshMs) {
		if (r + g + b < 0.01f) return;
		long ms = System.currentTimeMillis();
		if (facesFor == null || facesFor.distanceToSqr(light) > 0.04 || Math.abs(facesReach - reach) > 0.25f
				|| ms - facesAt > refreshMs) {
			faces = findLitFaces(light, onlyInFront, reach);
			facesAt = ms;
			facesFor = light;
			facesReach = reach;
		}
		if (faces.isEmpty()) return;

		List<LitFace> list = faces;
		SubmitNodeCollector out = ctx.submitNodeCollector();
		PoseStack pose = ctx.poseStack();
		// Without shaders, "lightning" is the vanilla way to add light on top of what's there
		// (additive blending). "eyes" isn't additive in vanilla 26.3 (it painted the ground in
		// flat colour, black for dark scenes) but shader packs light it nicely, so it's used then.
		boolean tint = tintGlow();
		RenderType type = emissiveGlow() ? RenderTypes.eyes(GlowTexture.get())
				: tint ? RenderTypes.entityTranslucent(GlowTexture.get()) : RenderTypes.lightning();
		// soft tint: the light's colour (brightest channel = 1) laid over the blocks, see-through
		float max = Math.max(r, Math.max(g, b));
		float tr = r / max, tg = g / max, tb = b / max;
		pose.pushPose();
		pose.setIdentity(); // world-space corners, relative to the camera
		out.submitCustomGeometry(pose, type, (p, vc) -> {
			for (LitFace f : list) {
				for (int i = 0; i < 4; i++) {
					Vec3 c = f.corners()[i];
					float l = f.light()[i];
					int color = tint
							? channel(Math.min(0.6f, l * max * 0.32f)) << 24 | channel(tr) << 16 | channel(tg) << 8 | channel(tb)
							: 0xFF000000 | channel(r * l) << 16 | channel(g * l) << 8 | channel(b * l);
					// the middle of the glow texture is plain white
					vc.addVertex(p, (float) (c.x - cam.pos.x), (float) (c.y - cam.pos.y), (float) (c.z - cam.pos.z))
							.setColor(color).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY)
							.setLight(FULL_BRIGHT).setNormal(p, 0f, 1f, 0f);
				}
			}
		});
		pose.popPose();
	}

	/** Forgets the cached faces (the light moved somewhere new). */
	public void reset() {
		facesFor = null;
		faces = List.of();
	}

	// ---------------------------------------------------------------- shaders

	private static Boolean irisPresent;
	private static java.lang.reflect.Method irisInstance, irisInUse;

	/** True when Iris is installed and a shader pack is on (looked up by reflection: Iris is optional). */
	public static boolean shaderPackInUse() {
		if (irisPresent == null) {
			irisPresent = false;
			try {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				irisInstance = api.getMethod("getInstance");
				irisInUse = api.getMethod("isShaderPackInUse");
				irisPresent = true;
			} catch (ReflectiveOperationException | LinkageError ignored) {
			}
		}
		if (!irisPresent) return false;
		try {
			return (boolean) irisInUse.invoke(irisInstance.invoke(null));
		} catch (ReflectiveOperationException | RuntimeException e) {
			irisPresent = false;
			return false;
		}
	}

	/**
	 * Whether to draw glow the "emissive" way (looks great under most shader packs) or the
	 * additive way (vanilla, and packs like Photon where emissive came out black).
	 */
	public static boolean emissiveGlow() {
		return dev.k3ytel.hearnodub.client.config.HearNoDubConfig.get().glowMode
				== dev.k3ytel.hearnodub.client.config.GlowMode.EMISSIVE && shaderPackInUse();
	}

	/**
	 * Soft tint: plain see-through colour over the blocks. The safe choice with shader packs
	 * (additive glow turned white and emissive turned black under Photon). Auto uses it
	 * whenever a shader pack is on; without shaders Auto stays additive.
	 */
	public static boolean tintGlow() {
		var mode = dev.k3ytel.hearnodub.client.config.HearNoDubConfig.get().glowMode;
		if (mode == dev.k3ytel.hearnodub.client.config.GlowMode.TINT) return true;
		if (mode != dev.k3ytel.hearnodub.client.config.GlowMode.AUTO || !shaderPackInUse()) return false;
		String name = packName();
		if (!name.equals(loggedPack)) {
			loggedPack = name;
			dev.k3ytel.hearnodub.client.HearNoDubClient.LOGGER.info("Shader pack in use: '{}' (glow drawn as a soft tint)", name);
		}
		return true;
	}

	private static String loggedPack;

	private static long packCheckedAt;
	private static String packName = "";

	/** The shader pack's file name in lower case ("" if unknown), looked up by reflection. */
	public static String packName() {
		long now = System.currentTimeMillis();
		if (now - packCheckedAt < 3000) return packName;
		packCheckedAt = now;
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			Object name = iris.getMethod("getCurrentPackName").invoke(null);
			packName = name == null ? "" : name.toString().toLowerCase(java.util.Locale.ROOT);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			packName = "";
		}
		return packName;
	}

	private static int channel(float v) {
		return Math.max(0, Math.min(255, Math.round(v * 255)));
	}

	// ---------------------------------------------------------------- finding faces

	/**
	 * Block faces within reach of the light that face it: solid on that side, with open space
	 * in front, in front of the light (when onlyInFront is set), and with nothing solid between
	 * the face and the light. Each corner gets its own brightness, so the light fades smoothly.
	 */
	private static List<LitFace> findLitFaces(Vec3 light, Vec3 onlyInFront, float reach) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return List.of();
		ClientLevel level = mc.level;
		List<LitFace> out = new ArrayList<>();
		int rr = (int) Math.ceil(reach);
		BlockPos origin = BlockPos.containing(light);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -rr; dx <= rr; dx++) {
			for (int dy = -rr; dy <= rr; dy++) {
				for (int dz = -rr; dz <= rr; dz++) {
					pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					var state = level.getBlockState(pos);
					if (state.isAir()) continue;
					for (Direction d : Direction.values()) {
						Vec3 faceCenter = Vec3.atCenterOf(pos).add(d.getUnitVec3().scale(0.5));
						Vec3 toLight = light.subtract(faceCenter);
						double dist = toLight.length();
						if (dist > reach || dist < 1e-3) continue;
						Vec3 n = d.getUnitVec3();
						if (n.dot(toLight) <= 0.05 * dist) continue; // facing away from the light
						if (onlyInFront != null && faceCenter.subtract(light).dot(onlyInFront) < -0.6) continue;
						if (!state.isFaceSturdy(level, pos, d)) continue;
						BlockPos front = pos.relative(d);
						if (!level.getBlockState(front).getCollisionShape(level, front).isEmpty()) continue;
						if (blocked(level, faceCenter.add(n.scale(0.05)), light)) continue;
						out.add(face(pos, d, light, reach));
						if (out.size() > 4000) return out;
					}
				}
			}
		}
		return out;
	}

	/** True when a solid block sits between the two points (so the light can't reach). */
	public static boolean blocked(ClientLevel level, Vec3 from, Vec3 to) {
		var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
				CollisionContext.empty()));
		return hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceToSqr(to) > 0.04;
	}

	/** The face's four corners (a hair off the block) with how much light reaches each. */
	private static LitFace face(BlockPos pos, Direction d, Vec3 light, float reach) {
		Vec3 n = d.getUnitVec3();
		Vec3 c = Vec3.atCenterOf(pos).add(n.scale(0.5 + 0.004));
		// two axes along the face, in an order that makes the quad face outward
		Vec3 a, b;
		switch (d) {
			case UP -> { a = new Vec3(0, 0, 0.5); b = new Vec3(0.5, 0, 0); }
			case DOWN -> { a = new Vec3(0.5, 0, 0); b = new Vec3(0, 0, 0.5); }
			case NORTH -> { a = new Vec3(0, 0.5, 0); b = new Vec3(0.5, 0, 0); }
			case SOUTH -> { a = new Vec3(0.5, 0, 0); b = new Vec3(0, 0.5, 0); }
			case WEST -> { a = new Vec3(0, 0, 0.5); b = new Vec3(0, 0.5, 0); }
			default -> { a = new Vec3(0, 0.5, 0); b = new Vec3(0, 0, 0.5); } // EAST
		}
		Vec3[] corners = {
				c.subtract(a).subtract(b), c.add(a).subtract(b), c.add(a).add(b), c.subtract(a).add(b)};
		float[] lit = new float[4];
		for (int i = 0; i < 4; i++) {
			Vec3 toLight = light.subtract(corners[i]);
			double dist = toLight.length();
			double falloff = Math.max(0, 1 - dist / reach);
			double facingLight = dist < 1e-3 ? 1 : Math.max(0, n.dot(toLight) / dist);
			lit[i] = (float) (falloff * falloff * (0.35 + 0.65 * facingLight));
		}
		return new LitFace(corners, lit);
	}
}
