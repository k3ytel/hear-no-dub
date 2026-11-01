package dev.k3ytel.hearnodub.client.video;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.k3ytel.hearnodub.client.HearNoDubClient;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The TV: a video screen fixed in the world. One per world (and dimension), saved in
 * config/hear-no-dub/tvs.json so it's still there next time.
 */
public final class TvScreen {
	private TvScreen() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = HearNoDubConfig.DIR.resolve("tvs.json");
	/** Biggest TV "Fit the wall" makes, in blocks. */
	private static final int MAX_COLS = 24, MAX_ROWS = 14;

	/** How the TV is attached to its anchor point. */
	public enum Anchor {
		/** Flat on a wall: the anchor is the middle of the screen. */
		WALL,
		/** Standing on top of a block: the anchor is the middle of the bottom edge. */
		STAND,
		/** Hanging under a ceiling: the anchor is the middle of the top edge. */
		HANG,
		/** In the air: the anchor is the middle of the screen. */
		FLOAT
	}

	public static final class Tv {
		public Anchor anchor = Anchor.FLOAT;
		public double x, y, z;
		/** Horizontal direction the screen faces (toward the viewer). */
		public double nx, nz = 1;
		/** Max width and height in blocks; the picture fits inside keeping its shape. maxH 0 = no limit. */
		public float maxW = 3, maxH = 0;

		public Vec3 anchorPos() {
			return new Vec3(x, y, z);
		}

		public Vec3 normal() {
			return new Vec3(nx, 0, nz);
		}

		/** Screen width for a picture of this shape (height / width). */
		public float width(float aspect) {
			float w = maxW;
			if (maxH > 0 && w * aspect > maxH) w = maxH / aspect;
			return w;
		}
	}

	private static Map<String, Tv> tvs;

	/** The TV in the world you're in now, or null. */
	public static Tv current() {
		String key = worldKey();
		return key == null ? null : all().get(key);
	}

	/**
	 * The place key: puts the TV on the block face you look at (within reach), or floating in
	 * front of you. Also switches the video on and to TV mode.
	 */
	public static void place(Minecraft mc) {
		Player player = mc.player;
		ClientLevel level = mc.level;
		String key = worldKey();
		if (player == null || level == null || key == null) return;
		HearNoDubConfig cfg = HearNoDubConfig.get();

		// Horizontal direction from where you look back toward you
		Vec3 look = player.getViewVector(1f);
		Vec3 toYou = new Vec3(-look.x, 0, -look.z);
		if (toYou.lengthSqr() < 1e-6) toYou = Direction.fromYRot(player.getYRot()).getOpposite().getUnitVec3();
		toYou = toYou.normalize();
		Direction toYouCardinal = Direction.fromYRot(player.getYRot()).getOpposite();

		Tv tv = new Tv();
		tv.maxW = cfg.videoTvWidth;
		Component msg;
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = bhr.getBlockPos();
			Direction face = bhr.getDirection();
			Vec3 faceCenter = Vec3.atCenterOf(pos).add(face.getUnitVec3().scale(0.5));
			if (face.getAxis().isHorizontal()) {
				tv.anchor = Anchor.WALL;
				setNormal(tv, face.getUnitVec3());
				Vec3 center = faceCenter;
				if (cfg.videoTvFitWall) {
					int[] r = fitWall(level, pos, face);
					if (r != null) {
						Vec3 right = rightOf(face);
						center = faceCenter.add(right.scale((r[0] + r[1]) / 2.0)).add(0, (r[2] + r[3]) / 2.0, 0);
						tv.maxW = (r[1] - r[0] + 1) - 0.1f;
						tv.maxH = (r[3] - r[2] + 1) - 0.1f;
					}
				}
				setPos(tv, center);
				msg = Component.translatable("hear-no-dub.msg.tv_wall", fmt(tv.maxW), tv.maxH > 0 ? fmt(tv.maxH) : "–");
			} else if (face == Direction.UP) {
				tv.anchor = Anchor.STAND;
				setNormal(tv, toYouCardinal.getUnitVec3());
				setPos(tv, faceCenter);
				msg = Component.translatable("hear-no-dub.msg.tv_stand");
			} else {
				tv.anchor = Anchor.HANG;
				setNormal(tv, toYouCardinal.getUnitVec3());
				setPos(tv, faceCenter);
				msg = Component.translatable("hear-no-dub.msg.tv_hang");
			}
		} else {
			tv.anchor = Anchor.FLOAT;
			setNormal(tv, toYou);
			setPos(tv, player.getEyePosition(1f).add(look.scale(cfg.videoTvDistance)));
			msg = Component.translatable("hear-no-dub.msg.tv_float");
		}
		all().put(key, tv);
		save();

		boolean changed = !cfg.videoEnabled || cfg.videoMode != dev.k3ytel.hearnodub.client.config.VideoMode.TV;
		if (!cfg.videoEnabled) VideoCapture.get().resetFailures();
		cfg.videoEnabled = true;
		cfg.videoMode = dev.k3ytel.hearnodub.client.config.VideoMode.TV;
		if (changed) HearNoDubConfig.save();
		player.sendOverlayMessage(msg);
	}

	/** Makes the TV bigger (factor > 1) or smaller. */
	public static void resize(Minecraft mc, float factor) {
		Tv tv = current();
		if (tv == null) {
			if (mc.player != null) mc.player.sendOverlayMessage(Component.translatable("hear-no-dub.msg.tv_none", placeKeyName()));
			return;
		}
		tv.maxW = Math.max(0.5f, Math.min(64f, tv.maxW * factor));
		if (tv.maxH > 0) tv.maxH = Math.max(0.3f, Math.min(64f, tv.maxH * factor));
		save();
		if (mc.player != null) mc.player.sendOverlayMessage(Component.translatable("hear-no-dub.msg.tv_size", fmt(tv.maxW)));
	}

	public static String placeKeyName() {
		return HearNoDubClient.videoPlaceKey == null ? "B" : HearNoDubClient.videoPlaceKey.getTranslatedKeyMessage().getString();
	}

	// ---------------------------------------------------------------- fit the wall

	/** "Right" along a wall facing this way, as seen by someone looking at it. */
	public static Vec3 rightOf(Direction face) {
		return new Vec3(face.getStepZ(), 0, -face.getStepX());
	}

	/**
	 * The biggest flat wall area around the clicked block: blocks with a solid face on that
	 * side and nothing solid in front. Grows one row or column at a time, evenly in all
	 * directions. Returns {left, right, bottom, top} in blocks from the clicked one, or null.
	 */
	private static int[] fitWall(ClientLevel level, BlockPos pos, Direction face) {
		if (!wallCell(level, pos, face)) return null;
		int rx = face.getStepZ(), rz = -face.getStepX();
		int l = 0, r = 0, b = 0, t = 0;
		boolean grew = true;
		while (grew) {
			grew = false;
			if (r - l + 1 < MAX_COLS && column(level, pos, face, rx, rz, r + 1, b, t)) { r++; grew = true; }
			if (r - l + 1 < MAX_COLS && column(level, pos, face, rx, rz, l - 1, b, t)) { l--; grew = true; }
			if (t - b + 1 < MAX_ROWS && row(level, pos, face, rx, rz, t + 1, l, r)) { t++; grew = true; }
			if (t - b + 1 < MAX_ROWS && row(level, pos, face, rx, rz, b - 1, l, r)) { b--; grew = true; }
		}
		return new int[]{l, r, b, t};
	}

	private static boolean column(ClientLevel level, BlockPos pos, Direction face, int rx, int rz, int i, int b, int t) {
		for (int j = b; j <= t; j++) if (!wallCell(level, pos.offset(rx * i, j, rz * i), face)) return false;
		return true;
	}

	private static boolean row(ClientLevel level, BlockPos pos, Direction face, int rx, int rz, int j, int l, int r) {
		for (int i = l; i <= r; i++) if (!wallCell(level, pos.offset(rx * i, j, rz * i), face)) return false;
		return true;
	}

	private static boolean wallCell(ClientLevel level, BlockPos pos, Direction face) {
		BlockState wall = level.getBlockState(pos);
		if (!wall.isFaceSturdy(level, pos, face)) return false;
		BlockPos front = pos.relative(face);
		return level.getBlockState(front).getCollisionShape(level, front).isEmpty();
	}

	// ---------------------------------------------------------------- storage

	private static void setPos(Tv tv, Vec3 p) {
		tv.x = p.x;
		tv.y = p.y;
		tv.z = p.z;
	}

	private static void setNormal(Tv tv, Vec3 n) {
		Vec3 h = new Vec3(n.x, 0, n.z).normalize();
		tv.nx = h.x;
		tv.nz = h.z;
	}

	/** "sp:<world name>|<dimension>" or "mp:<server address>|<dimension>". */
	private static String worldKey() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		String world;
		IntegratedServer sp = mc.getSingleplayerServer();
		ServerData server = mc.getCurrentServer();
		if (sp != null) world = "sp:" + sp.getWorldData().getLevelName();
		else if (server != null) world = "mp:" + server.ip;
		else world = "unknown";
		return world + "|" + mc.level.dimension();
	}

	private static Map<String, Tv> all() {
		if (tvs == null) {
			tvs = new HashMap<>();
			if (Files.exists(FILE)) {
				try (Reader r = Files.newBufferedReader(FILE)) {
					Type type = new TypeToken<Map<String, Tv>>() {}.getType();
					Map<String, Tv> loaded = GSON.fromJson(r, type);
					if (loaded != null) tvs.putAll(loaded);
				} catch (Exception e) {
					HearNoDubClient.LOGGER.warn("Could not read {}", FILE, e);
				}
			}
		}
		return tvs;
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE)) {
				GSON.toJson(all(), w);
			}
		} catch (Exception e) {
			HearNoDubClient.LOGGER.warn("Could not save {}", FILE, e);
		}
	}

	private static String fmt(float v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}
}
