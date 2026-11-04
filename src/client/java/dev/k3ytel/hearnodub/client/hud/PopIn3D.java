package dev.k3ytel.hearnodub.client.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.config.LetterAnimation;
import dev.k3ytel.hearnodub.client.config.LetterFont;
import dev.k3ytel.hearnodub.client.config.LetterMaterial;
import dev.k3ytel.hearnodub.client.config.LetterSpawn;
import dev.k3ytel.hearnodub.client.config.LetterStyle;
import dev.k3ytel.hearnodub.client.config.LyricsReveal;
import dev.k3ytel.hearnodub.client.media.AudioLevel;
import dev.k3ytel.hearnodub.client.render.AwtGlyphs;
import dev.k3ytel.hearnodub.client.render.BlockGlow;
import dev.k3ytel.hearnodub.client.render.GlyphBitmaps;
import dev.k3ytel.hearnodub.client.render.LetterMesh;
import dev.k3ytel.hearnodub.client.render.SubStyle;
import dev.k3ytel.hearnodub.client.render.SubText;
import dev.k3ytel.hearnodub.client.subs.Chorus;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import dev.k3ytel.hearnodub.client.subs.SubtitleTrack;
import dev.k3ytel.hearnodub.client.video.GlowTexture;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * "3D Pop-in": lyrics and subtitles land in the world as real 3D letters standing on blocks.
 * <ul>
 *   <li>Whole lines, or (word mode) every word on its own spot as it's sung, always in view,
 *       ahead of you when you walk, never in your face.</li>
 *   <li>Letter styles (thick, chiseled, balloon, neon, glass, hologram, flat, cubes), fonts, and
 *       materials: text colour, rainbow, the block they stand on, gold, diamond...</li>
 *   <li>Karaoke: words light up, lift, bounce, glow, wave or wobble as they're sung (the same
 *       settings as the lyrics display), and everything moves with the music's real loudness.</li>
 *   <li>Letters land with dust, a bump and a squash; the last line of a song goes out with
 *       fireworks. Rain soaks them and they drip; snow settles on their tops.</li>
 * </ul>
 */
public final class PopIn3D {
	private PopIn3D() {}

	private static final int FULL_BRIGHT = 15728880;
	/** Size of one letter pixel at scale 1: one block pixel. */
	private static final float PX = 1f / 16f;
	private static final int WRAP_PX = 120;
	private static final int LINE_STEP = 10; // 9 px line + 1 px gap
	private static final Random RANDOM = new Random();
	/** Most words kept standing at once in word mode. */
	private static final int MAX_WORDS = 12;

	/** One letter. Positions are text pixels, x from the line's left edge. */
	private static final class Glyph {
		int cp;
		Style style;
		FormattedCharSequence seq;
		GlyphBitmaps.Glyph bitmap;
		float x, lineTop, width;
		int color; // RGB
		int index;
		int word;
		int letterInWord, lettersInWord;
		long appearAt = -1;
		boolean sparked, puffed, landed;
		/** Gone in the finale's fireworks. */
		boolean shattered;

		Glyph copy() {
			Glyph g = new Glyph();
			g.cp = cp;
			g.style = style;
			g.seq = seq;
			g.bitmap = bitmap;
			g.x = x;
			g.lineTop = lineTop;
			g.width = width;
			g.color = color;
			g.index = index;
			g.word = word;
			g.letterInWord = letterInWord;
			g.lettersInWord = lettersInWord;
			return g;
		}
	}

	/** A line (or a single word) standing somewhere in the world. */
	private static final class Placed {
		long key, group = Long.MIN_VALUE;
		Vec3 anchor; // on the ground, middle of the line
		Vec3 right, back;
		Quaternionf rotation;
		boolean grounded, onPlants;
		float size = 1; // extra scale for far away lines
		List<Glyph> glyphs = new ArrayList<>();
		float widthPx, heightPx;
		long spawnAt, leaveAt = -1;
		/** How far away it was placed (words laid out ahead while you move can start far off). */
		double spawnDist;
		final BlockGlow glow = new BlockGlow();
		float glowR, glowG, glowB;
		ClientLevel level;
		LetterFont font;
		LetterStyle style;
		LetterMaterial material;
		LetterSpawn spawn;
		boolean karaoke;
		long[] ws = new long[0], we = new long[0];
		BlockState ground;
		BlockPos groundPos;
		boolean finale, exploded;
		long flashAt;
		/** Choruses are bigger and shimmer. */
		boolean chorus;
		/** Floating above a player or mob. */
		Entity follow;
		/** Weather on the letters: 0..1. */
		float wet, snow;

		float unit(HearNoDubConfig cfg) {
			return PX * cfg.letterScale * size;
		}
	}

	private record WordTime(long start, long end, boolean real) {}

	private static final List<Placed> placed = new ArrayList<>();
	private static Placed current;
	private static Vec3 lastSpot;
	private static long wordGroup = Long.MIN_VALUE;
	private static Placed wordTemplate;
	private static int wordsSpawned;
	/** Word mode: the last word placed and the first word of its row on screen (for reading order). */
	private static Placed lastWord, rowStart;
	/** The last whole line placed, and which side of your path lines go to while you move (flips each line). */
	private static Placed lastLine;
	private static int trailSide = 1;
	/** Faster than this (blocks per second) words are laid out ahead along your path. */
	private static final double TRAIL_SPEED = 2.5;
	private static long lastFrame, lastSound, lastBeats;

	public static void clear() {
		placed.clear();
		current = null;
		wordTemplate = null;
		wordGroup = Long.MIN_VALUE;
	}

	// ================================================================ ticking

	public static void tick(Minecraft mc) {
		mobsLook(mc, HearNoDubConfig.get());
	}

	// ================================================================ per frame

	public static void render(LevelRenderContext ctx, Minecraft mc, HearNoDubConfig cfg, CameraRenderState cam,
			List<SubtitleTrack.Cue> cues) {
		long now = System.currentTimeMillis();
		float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		ClientLevel level = mc.level;
		placed.removeIf(p -> p.level != level);
		if (current != null && current.level != level) current = null;

		SubtitleManager m = SubtitleManager.get();
		long t = m.subtitleTimeMs();
		SubStyle style = SubStyle.resolve(cfg, true);
		Font font = mc.font;

		if (current != null && (current.font != cfg.letterFont || current.style != cfg.letterStyle
				|| current.material != cfg.letterMaterial || current.spawn != cfg.letterSpawn)) {
			placed.remove(current); // settings changed: rebuild right away
			current = null;
		}
		boolean wordMode = cfg.letterSpawn == LetterSpawn.WORD;
		if (!wordMode) endWordGroup(now);

		if (!cues.isEmpty()) {
			long end = 0;
			for (SubtitleTrack.Cue c : cues) end = Math.max(end, c.endMs());
			SubText.Block full = SubText.build(font, cues, null, end - 1, cfg, WRAP_PX, style.textColor());
			if (!full.isEmpty()) {
				List<WordTime> words = wordTimes(cues);
				boolean anyReal = words.stream().anyMatch(WordTime::real);
				boolean karaoke = wordMode || cfg.letterKaraoke && (anyReal || m.isLyricsTrack());
				boolean finale = isLastLine(m, cues);
				boolean chorus = cfg.lyricsChorus && m.isLyricsTrack() && Chorus.anyChorus(m.allCues(), cues);
				boolean head = cfg.letterSpawn == LetterSpawn.HEAD;
				if (wordMode) {
					if (current != null) {
						current.leaveAt = now;
						current = null;
					}
					wordMode(mc, cfg, cam, font, full, words, finale, chorus, now, t);
				} else {
					if (current == null || current.key != full.key()) {
						if (current != null) current.leaveAt = now;
						current = layout(cfg, font, full, now);
						current.karaoke = karaoke;
						current.finale = finale;
						current.chorus = chorus;
						assignTimes(current, words, cues);
						if (head) {
							attachHead(mc, cfg, cam, current);
						} else {
							trailSide = -trailSide;
							place(mc, cfg, cam, current, null, new Trail(lastLine, trailSide, 2.2));
							lastLine = current;
						}
						placed.add(current);
					}
					if (current.karaoke) {
						revealKaraoke(cfg, current, now, t, false);
					} else {
						SubText.Block visible = SubText.build(font, cues, null, t, cfg, WRAP_PX, style.textColor());
						reveal(current, countCodepoints(visible), now, cfg);
					}
				}
			}
		} else {
			if (current != null) {
				current.leaveAt = now;
				current = null;
			}
			endWordGroup(now);
		}

		for (Iterator<Placed> it = placed.iterator(); it.hasNext(); ) {
			Placed p = it.next();
			if (p.leaveAt > 0 && now - p.leaveAt > exitLength(p)) {
				it.remove();
				continue;
			}
			if (p.follow == null && p.anchor.distanceTo(cam.pos) > Math.max(cfg.letterDistance * 3, p.spawnDist + 8) + 24) {
				it.remove();
				if (p == current) current = null;
				continue;
			}
			if (p.follow != null) updateFollow(cam, p);
			weather(mc, cfg, p, dt);
			draw(ctx, mc, cfg, cam, style, p, now, t);
		}
	}

	// ================================================================ word timing

	/** Every word of the cues on screen, in order, with when it's sung (real or guessed). */
	private static List<WordTime> wordTimes(List<SubtitleTrack.Cue> cues) {
		List<WordTime> out = new ArrayList<>();
		for (SubtitleTrack.Cue c : cues) {
			String[] parts = c.text().replace('\n', ' ').trim().split("\\s+");
			if (parts.length == 0 || parts[0].isEmpty()) continue;
			if (c.hasWordTimes() && c.words().length == parts.length) {
				for (int i = 0; i < parts.length; i++) {
					out.add(new WordTime(c.startMs() + c.words()[i], c.startMs() + c.wordEnd(i), true));
				}
				continue;
			}
			int chars = 0;
			for (String p : parts) chars += p.length() + 1;
			long span = Math.min((long) (c.durationMs() * 0.85), Math.max(600, chars * 95L));
			long at = c.startMs();
			for (String p : parts) {
				long d = Math.max(90, span * (p.length() + 1) / Math.max(1, chars));
				out.add(new WordTime(at, at + d, false));
				at += d;
			}
		}
		return out;
	}

	/** Gives each word of a placed line its timing (by position; spread evenly if the counts differ). */
	private static void assignTimes(Placed p, List<WordTime> words, List<SubtitleTrack.Cue> cues) {
		int count = 0;
		for (Glyph g : p.glyphs) count = Math.max(count, g.word + 1);
		p.ws = new long[count];
		p.we = new long[count];
		if (words.size() == count) {
			for (int i = 0; i < count; i++) {
				p.ws[i] = words.get(i).start();
				p.we[i] = words.get(i).end();
			}
			return;
		}
		long start = Long.MAX_VALUE, end = 0;
		for (WordTime w : words) {
			start = Math.min(start, w.start());
			end = Math.max(end, w.end());
		}
		for (SubtitleTrack.Cue c : cues) {
			start = Math.min(start, c.startMs());
			end = Math.max(end, (long) (c.startMs() + c.durationMs() * 0.85));
		}
		if (start == Long.MAX_VALUE) {
			start = 0;
			end = 1;
		}
		for (int i = 0; i < count; i++) {
			p.ws[i] = start + (end - start) * i / Math.max(1, count);
			p.we[i] = start + (end - start) * (i + 1) / Math.max(1, count);
		}
	}

	private static boolean isLastLine(SubtitleManager m, List<SubtitleTrack.Cue> cues) {
		if (!m.isLyricsTrack()) return false;
		List<SubtitleTrack.Cue> all = m.allCues();
		for (int i = all.size() - 1; i >= 0; i--) {
			SubtitleTrack.Cue c = all.get(i);
			if (c.isBackground()) continue;
			for (SubtitleTrack.Cue x : cues) if (x.startMs() == c.startMs() && x.text().equals(c.text())) return true;
			return false;
		}
		return false;
	}

	// ================================================================ revealing letters

	private static int countCodepoints(SubText.Block b) {
		int[] n = {0};
		for (SubText.Line l : b.lines()) l.colored().accept((i, s, cp) -> {
			n[0]++;
			return true;
		});
		return n[0];
	}

	/** Without word timing: letters roll in one after another as the line is revealed. */
	private static void reveal(Placed p, int visibleCount, long now, HearNoDubConfig cfg) {
		long step = stagger(cfg, p.glyphs.size());
		for (Glyph g : p.glyphs) {
			if (g.appearAt >= 0 || g.index >= visibleCount) continue;
			g.appearAt = Math.max(now, p.spawnAt + g.index * step);
		}
	}

	/** With word timing: each letter arrives when its word (or its part of the word) is sung. */
	private static void revealKaraoke(HearNoDubConfig cfg, Placed p, long now, long t, boolean wordMode) {
		LyricsReveal reveal = wordMode && cfg.lyricsReveal == LyricsReveal.DIMMED ? LyricsReveal.WORD_BY_WORD : cfg.lyricsReveal;
		for (Glyph g : p.glyphs) {
			if (g.appearAt >= 0 || g.cp == ' ' || g.word >= p.ws.length) continue;
			long ws = p.ws[g.word], we = Math.max(ws + 1, p.we[g.word]);
			float k = g.letterInWord / (float) Math.max(1, g.lettersInWord);
			long at = switch (reveal) {
				case DIMMED -> cfg.lyricsFill ? ws + (long) ((we - ws) * 0.9f * k) : ws + g.letterInWord * 20L;
				case LETTER_BY_LETTER -> ws + (long) ((we - ws) * 0.85f * k);
				default -> ws + g.letterInWord * 22L;
			};
			if (t >= at) g.appearAt = now - Math.min(400, t - at);
		}
	}

	private static long stagger(HearNoDubConfig cfg, int n) {
		long base = switch (cfg.letterAnimation) {
			case DROP -> 30;
			case RISE -> 14;
			case PARTICLES -> 26;
			case FADE -> 12;
		};
		return Math.min(base, 520 / Math.max(1, n));
	}

	private static long exitLength(Placed p) {
		return Math.min(p.glyphs.size() * 16L, 260) + 450;
	}

	// ================================================================ word mode

	/** Every word lands on its own spot right as it's sung, near the previous one, in front of you. */
	private static void wordMode(Minecraft mc, HearNoDubConfig cfg, CameraRenderState cam, Font font, SubText.Block full,
			List<WordTime> words, boolean finale, boolean chorus, long now, long t) {
		if (full.key() != wordGroup) {
			endWordGroup(now);
			wordGroup = full.key();
			wordTemplate = layout(cfg, font, full, now);
			assignTimes(wordTemplate, words, List.of());
			wordsSpawned = 0;
			rowStart = null; // a new line starts a new row
		}
		Placed tpl = wordTemplate;
		while (wordsSpawned < tpl.ws.length && t >= tpl.ws[wordsSpawned] - 160) {
			int w = wordsSpawned++;
			Placed p = extractWord(tpl, w, now);
			if (p == null) continue;
			p.group = wordGroup;
			p.finale = finale && w == tpl.ws.length - 1;
			p.ws = new long[] {tpl.ws[w]};
			p.we = new long[] {tpl.we[w]};
			p.chorus = chorus;
			float base = PX * cfg.letterScale * (p.chorus ? 1.25f : 1f);
			Hint hint = nextWordHint(mc, cfg, cam, p.widthPx * base, (p.heightPx + 2) * base);
			if (w == 0) trailSide = -trailSide; // each line on the other side of your path
			double lead = Math.max(0, (tpl.ws[w] - t) / 1000.0) + 1.4;
			place(mc, cfg, cam, p, hint, new Trail(lastWord, trailSide, lead));
			if (hint == null || hint.newRow || rowStart == null) rowStart = p;
			lastWord = p;
			placed.add(p);
			int alive = 0; // too many standing: the oldest ones leave
			for (int i = placed.size() - 1; i >= 0; i--) {
				Placed q = placed.get(i);
				if (q.spawn != LetterSpawn.WORD || q.leaveAt > 0) continue;
				if (++alive > MAX_WORDS) q.leaveAt = now;
			}
		}
		for (Placed p : placed) {
			if (p.group == wordGroup && p.leaveAt < 0) revealKaraoke(cfg, p, now, t, true);
		}
	}

	// ================================================================ above a head

	/** Lines above a player or mob (the one you're fighting first). */
	private static void attachHead(Minecraft mc, HearNoDubConfig cfg, CameraRenderState cam, Placed p) {
		p.follow = WorldSubtitles.bubbleTarget(mc, cfg, cam, p.key);
		p.grounded = false;
		p.onPlants = false;
		p.size = 0.8f;
		updateFollow(cam, p);
		if (p.anchor == null) {
			p.anchor = cam.pos.add(cameraLook(cam).scale(4));
			face(p, cam.pos);
		}
		lastSpot = p.anchor;
	}

	private static void updateFollow(CameraRenderState cam, Placed p) {
		Entity e = p.follow;
		if (e == null) return;
		if (e.isAlive()) {
			double lift = e.getBbHeight() + 0.45 + (e.shouldShowName() ? 0.3 : 0);
			p.anchor = e.getPosition(cam.cameraEntityPartialTicks).add(0, lift, 0);
		}
		face(p, cam.pos);
	}

	/** Turns a line toward a point (around the vertical axis only). */
	private static void face(Placed p, Vec3 toward) {
		Vec3 toCam = toward.subtract(p.anchor);
		Vector3f back = new Vector3f((float) toCam.x, 0, (float) toCam.z);
		if (back.lengthSquared() < 1e-6f) back.set(0, 0, 1);
		back.normalize();
		Vector3f up = new Vector3f(0, 1, 0);
		Vector3f right = new Vector3f(back.z, 0, -back.x);
		p.rotation = new Quaternionf().setFromNormalized(new Matrix3f(right, up, back));
		p.right = new Vec3(right.x, right.y, right.z);
		p.back = new Vec3(back.x, back.y, back.z);
	}

	/** Director: open sky, a view behind, water nearby, and toward the sun at sunrise/sunset. */
	private static double director(ClientLevel level, Vec3 g, Vec3 back) {
		double s = 0;
		BlockPos above = BlockPos.containing(g.x, g.y + 1.2, g.z);
		if (level.getBrightness(LightLayer.SKY, above) >= 14) s += 0.8;
		Vec3 behind = g.subtract(back.scale(2.5)).add(0, 0.5, 0);
		if (clip(level, behind, behind.subtract(0, 5, 0)).getType() == HitResult.Type.MISS) s += 1.0; // an edge with a view
		int[][] around = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
		for (int[] d : around) {
			if (!level.getFluidState(BlockPos.containing(g.x + d[0], g.y - 0.6, g.z + d[1])).isEmpty()) {
				s += 0.7;
				break;
			}
		}
		long tod = Math.floorMod(level.getOverworldClockTime(), 24000L);
		double f = tod / 24000.0 - 0.25; // 0 = noon
		double sunX = -Math.sin(f * 2 * Math.PI), sunY = Math.cos(f * 2 * Math.PI);
		if (Math.abs(sunY) < 0.4 && -back.x * Math.signum(sunX) > 0.5) s += 1.0; // the sun behind the words
		return s;
	}

	/** Rain soaks the letters (darker, shiny, dripping); snow settles on their tops. */
	private static void weather(Minecraft mc, HearNoDubConfig cfg, Placed p, float dt) {
		ClientLevel level = mc.level;
		if (!cfg.letterWeather || level == null || p.anchor == null) {
			p.wet = p.snow = 0;
			return;
		}
		BlockPos wp = BlockPos.containing(p.anchor.x, p.anchor.y + 1, p.anchor.z);
		boolean rain = level.isRainingAt(wp);
		boolean snow = false;
		if (!rain && level.isRaining() && level.getBrightness(LightLayer.SKY, wp) >= 14) {
			try {
				snow = level.getBiome(wp).value().getPrecipitationAt(wp, level.getSeaLevel()) == Biome.Precipitation.SNOW;
			} catch (RuntimeException ignored) {
			}
		}
		p.wet += ((rain ? 1 : 0) - p.wet) * Math.min(1, dt / 3f);
		p.snow = clamp01(p.snow + (snow ? dt / 25f : -dt / 8f));
		if (p.wet > 0.4f && !mc.isPaused() && RANDOM.nextInt(5) == 0 && !p.glyphs.isEmpty()) { // drips off the letters
			Glyph g = p.glyphs.get(RANDOM.nextInt(p.glyphs.size()));
			if (g.cp != ' ' && g.appearAt >= 0 && !g.shattered) {
				float u = p.unit(cfg);
				Vec3 at = glyphWorld(p, g, u).add(0, -3.5 * u, 0);
				level.addParticle(ParticleTypes.FALLING_WATER, at.x, at.y, at.z, 0, 0, 0);
			}
		}
	}

	/** Mobs near a line that just landed turn their heads to look at it. */
	private static void mobsLook(Minecraft mc, HearNoDubConfig cfg) {
		if (!cfg.letterMobsLook || mc.level == null || placed.isEmpty()) return;
		long now = System.currentTimeMillis();
		for (Placed p : placed) {
			if (p.leaveAt > 0 || p.follow != null || p.anchor == null || now - p.spawnAt > 3500) continue;
			Vec3 target = p.anchor.add(0, 0.3, 0);
			for (Mob mob : mc.level.getEntitiesOfClass(Mob.class, new AABB(target, target).inflate(10))) {
				Vec3 d = target.subtract(mob.getEyePosition());
				float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
				float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
				mob.setYHeadRot(yaw);
				mob.setXRot(pitch);
			}
		}
	}

	/** The words of the line that just ended sink away one after another. */
	private static void endWordGroup(long now) {
		if (wordGroup == Long.MIN_VALUE) return;
		int order = 0;
		for (Placed p : placed) {
			if (p.group == wordGroup && p.leaveAt < 0) p.leaveAt = now + 900 + order++ * 70L;
		}
		wordGroup = Long.MIN_VALUE;
		wordTemplate = null;
	}

	private static Placed extractWord(Placed tpl, int word, long now) {
		List<Glyph> letters = new ArrayList<>();
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
		for (Glyph g : tpl.glyphs) {
			if (g.word != word || g.cp == ' ') continue;
			Glyph c = g.copy();
			letters.add(c);
			minX = Math.min(minX, c.x);
			maxX = Math.max(maxX, c.x + c.width);
		}
		if (letters.isEmpty()) return null;
		Placed p = new Placed();
		p.key = tpl.key * 31 + word;
		p.spawnAt = now;
		p.level = tpl.level;
		p.font = tpl.font;
		p.style = tpl.style;
		p.material = tpl.material;
		p.spawn = LetterSpawn.WORD;
		p.karaoke = true;
		int i = 0;
		for (Glyph g : letters) {
			g.x -= minX;
			g.lineTop = -7f;
			g.word = 0;
			g.index = i++;
		}
		p.glyphs = letters;
		p.widthPx = maxX - minX;
		p.heightPx = LINE_STEP - 1;
		averageColor(p);
		return p;
	}

	// ================================================================ layout

	/** Builds the letters of a block of text in the chosen font (no position yet). */
	private static Placed layout(HearNoDubConfig cfg, Font font, SubText.Block block, long now) {
		Placed p = new Placed();
		p.key = block.key();
		p.spawnAt = now;
		p.level = Minecraft.getInstance().level;
		p.font = cfg.letterFont;
		p.style = cfg.letterStyle;
		p.material = cfg.letterMaterial;
		p.spawn = cfg.letterSpawn;
		int lines = block.lines().size();
		p.heightPx = lines * LINE_STEP - 1;
		LetterFont lf = cfg.letterFont;
		boolean system = AwtGlyphs.isSystemFont(lf);
		boolean mcFont = lf == LetterFont.MINECRAFT;
		float res = switch (cfg.letterStyle) {
			case CUBES -> 1.3f;
			case CHISELED, BALLOON, NEON -> 3f; // detailed shapes: a bit coarser keeps them fast
			default -> 4f;
		};
		int[] idx = {0};
		int[] word = {-1};
		boolean[] needWord = {true};
		float[] lineWidths = new float[lines];
		for (int li = 0; li < lines; li++) {
			SubText.Line line = block.lines().get(li);
			final float top = (li - (lines - 1)) * LINE_STEP - 7f; // the last line's baseline sits on the ground
			float[] x = {0};
			needWord[0] = true; // a new line always starts a new word
			line.colored().accept((i, s, cp) -> {
				Glyph g = new Glyph();
				g.cp = cp;
				g.style = s;
				g.seq = FormattedCharSequence.codepoint(cp, s);
				g.x = x[0];
				g.lineTop = top;
				TextColor tc = s.getColor();
				g.color = tc != null ? tc.getValue() & 0xFFFFFF : 0xFFFFFF;
				if (cp == ' ') {
					needWord[0] = true;
					g.word = Math.max(0, word[0]);
					g.width = system ? AwtGlyphs.spaceAdvance(lf, res) : font.width(g.seq);
				} else {
					if (needWord[0]) {
						word[0]++;
						needWord[0] = false;
					}
					g.word = word[0];
					g.bitmap = letter(lf, cp, res);
					if (g.bitmap == null) g.bitmap = GlyphBitmaps.get(cp); // letter missing from that font
					g.width = mcFont || g.bitmap == null ? font.width(g.seq) : g.bitmap.advance();
				}
				g.index = idx[0]++;
				x[0] += g.width;
				p.glyphs.add(g);
				return true;
			});
			lineWidths[li] = x[0];
			p.widthPx = Math.max(p.widthPx, x[0]);
		}
		int words = Math.max(0, word[0]) + 1;
		int[] perWord = new int[words];
		for (Glyph g : p.glyphs) {
			int li = Math.round((g.lineTop + 7f) / LINE_STEP) + (lines - 1);
			if (li >= 0 && li < lines) g.x += (p.widthPx - lineWidths[li]) / 2f;
			if (g.cp != ' ' && g.word < words) g.letterInWord = perWord[g.word]++;
		}
		for (Glyph g : p.glyphs) if (g.word < words) g.lettersInWord = perWord[g.word];
		averageColor(p);
		return p;
	}

	private static void averageColor(Placed p) {
		float sr = 0, sg = 0, sb = 0;
		int n = 0;
		for (Glyph g : p.glyphs) {
			if (g.cp == ' ') continue;
			sr += (g.color >> 16) & 255;
			sg += (g.color >> 8) & 255;
			sb += g.color & 255;
			n++;
		}
		if (n > 0) {
			p.glowR = sr / n / 255f;
			p.glowG = sg / n / 255f;
			p.glowB = sb / n / 255f;
		}
	}

	/** The letter in the chosen font (null = draw it as flat Minecraft text). */
	private static GlyphBitmaps.Glyph letter(LetterFont f, int cp, float res) {
		return switch (f) {
			case MINECRAFT -> GlyphBitmaps.get(cp);
			case GALACTIC -> runes("alt", cp);
			case ILLAGER -> runes("illageralt", cp);
			default -> AwtGlyphs.get(f, cp, res);
		};
	}

	private static GlyphBitmaps.Glyph runes(String font, int cp) {
		GlyphBitmaps.Glyph g = GlyphBitmaps.get(font, cp);
		if (g == null && Character.isUpperCase(cp)) g = GlyphBitmaps.get(font, Character.toLowerCase(cp));
		return g;
	}

	// ================================================================ placing

	/** Finds a spot, turns the line toward you and remembers what it stands on. */
	private static void place(Minecraft mc, HearNoDubConfig cfg, CameraRenderState cam, Placed p, Hint hint, Trail trail) {
		float base = PX * cfg.letterScale * (p.chorus ? 1.25f : 1f);
		Spot spot = trail != null ? trailSpot(mc, cfg, cam, p.widthPx * base, (p.heightPx + 2) * base, trail) : null;
		if (spot == null) spot = findSpot(mc, cfg, cam, p.widthPx * base, (p.heightPx + 2) * base, hint);
		p.anchor = spot.pos;
		p.spawnDist = spot.pos.distanceTo(cam.pos);
		p.grounded = spot.grounded;
		p.onPlants = spot.onPlants;
		p.size = spot.size * (p.chorus ? 1.25f : 1f);
		face(p, cam.pos);
		if (mc.level != null) {
			BlockPos below = BlockPos.containing(p.anchor.x, p.anchor.y - 0.05, p.anchor.z);
			BlockState st = mc.level.getBlockState(below);
			if (st.isAir() || st.getCollisionShape(mc.level, below).isEmpty()) {
				below = below.below();
				st = mc.level.getBlockState(below);
			}
			p.ground = st;
			p.groundPos = below;
		}
		lastSpot = p.anchor;
	}

	private record Spot(Vec3 pos, boolean grounded, boolean onPlants, float size) {}

	/** Moving fast: the line before (to keep order), which side of the path, and seconds until it should be read. */
	private record Trail(Placed prev, int side, double lead) {}

	/** Your movement in blocks per second (vertical only counts when flying). */
	private static Vec3 motion(Minecraft mc) {
		if (mc.player == null) return Vec3.ZERO;
		Vec3 v = mc.player.getDeltaMovement().scale(20);
		boolean flying = mc.player.getAbilities().flying || mc.player.isFallFlying();
		return flying ? v : new Vec3(v.x, 0, v.z);
	}

	/**
	 * Moving fast (walking, sprinting, flying): words land ahead along your path, far enough
	 * that when you reach them they're at a normal reading distance (speed × time until it's
	 * read + the middle of min/max distance; max distance doesn't apply). Each word lands
	 * farther along the path than the one before, beside the path on one side, never in it.
	 */
	private static Spot trailSpot(Minecraft mc, HearNoDubConfig cfg, CameraRenderState cam, float width, float height, Trail trail) {
		if (mc.player == null || mc.level == null) return null;
		Vec3 vel = motion(mc);
		double speed = vel.length();
		if (speed < TRAIL_SPEED) return null;
		ClientLevel level = mc.level;
		Vec3 dir = vel.normalize();
		Vec3 flatDir = new Vec3(dir.x, 0, dir.z);
		if (flatDir.lengthSqr() < 1e-4) return null; // straight up or down: no path to line up along
		flatDir = flatDir.normalize();
		double climb = dir.y / Math.max(0.2, Math.hypot(dir.x, dir.z)); // flying up/down: follow it
		Vec3 left = new Vec3(flatDir.z, 0, -flatDir.x);
		double normal = (cfg.letterMinDistance + cfg.letterDistance) / 2;
		double along0 = Math.min(160, speed * trail.lead() + normal);
		Placed prev = trail.prev();
		if (prev != null && prev.anchor != null && prev.leaveAt < 0 && placed.contains(prev)) {
			double prevAlong = prev.anchor.subtract(cam.pos).dot(flatDir);
			along0 = Math.max(along0, prevAlong + prev.widthPx * prev.unit(cfg) / 2 + width / 2 + 1.5);
		}
		View view = view(mc, cam, cam.pos);
		List<Rect> taken = occupied(view, cfg);
		double limX = view.tanH() * 0.86, limY = view.tanV() * 0.84;
		for (int k = 0; k < 40; k++) {
			double along = along0 + k * 0.9;
			// beside the path, a bit wider when far so it clears the crosshair; never more than ~6 blocks out
			double off = (Math.min(6, Math.max(2.5 + width / 2, along * 0.12)) + RANDOM.nextDouble() * 1.2) * trail.side();
			Vec3 p = cam.pos.add(flatDir.scale(along)).add(left.scale(off)).add(0, climb * along, 0);
			Vec3 g;
			boolean grounded = false;
			BlockHitResult down = clip(level, p.add(0, 2, 0), p.subtract(0, 10, 0));
			if (down.getType() != HitResult.Type.MISS && down.getDirection() == Direction.UP) {
				g = down.getLocation();
				grounded = true;
			} else {
				g = p.subtract(0, 1.0, 0); // nothing below (flying high): float a bit under eye level
			}
			float size = autoSize(cfg, normal); // sized for when you reach it, not for how far it starts
			double w = width * size, h = height * size;
			Vec3 toCam = cam.pos.subtract(g);
			Vec3 lineRight = new Vec3(toCam.z, 0, -toCam.x).normalize();
			double plant = grounded ? plantHeight(level, g) : 0;
			Vec3 at = g.add(0, plant, 0);
			if (!roomFor(level, at, lineRight, w, h)) continue;
			Rect r = rectOf(view, at, lineRight, w, h);
			if (r == null || r.x0() < -limX || r.x1() > limX || r.y0() < -limY || r.y1() > limY) continue;
			if (r.overlaps(CROSSHAIR, 0, 0)) continue;
			boolean clash = false;
			for (Rect o : taken) {
				if (r.overlaps(o, 0.012, 0.012)) {
					clash = true;
					break;
				}
			}
			if (clash) continue;
			if (blocked(level, cam.pos, at.add(0, h * 0.55, 0))) continue;
			return new Spot(at, grounded, plant > 0.05, size);
		}
		return null;
	}

	private record Score(double value, double plantTop) {}

	/** Word mode: where on screen the next word should go (x, baseline y) and how far away. */
	private record Hint(double x, double y, double dist, boolean newRow) {}

	/**
	 * Word mode: the next word goes right after the previous one on screen, on the same
	 * baseline; when the row is full it starts a new row below (like reading). Null when the
	 * previous word is gone or off screen (then the word lands anywhere free).
	 */
	private static Hint nextWordHint(Minecraft mc, HearNoDubConfig cfg, CameraRenderState cam, float width, float height) {
		Placed prev = lastWord;
		if (prev == null || prev.leaveAt > 0 || prev.anchor == null || !placed.contains(prev)) return null;
		View v = view(mc, cam, cam.pos);
		Rect pr = rectOf(v, prev, cfg);
		if (pr == null) return null;
		double limX = v.tanH() * 0.86, limY = v.tanV() * 0.84;
		if (pr.x1() < -limX || pr.x0() > limX || pr.y1() < -limY || pr.y0() > limY) return null;
		double dist = prev.anchor.distanceTo(cam.pos);
		double ww = width / dist, wh = height / dist; // this word's size on screen at that distance
		double gap = wh * 0.45;
		double x = pr.x1() + gap + ww / 2, y = pr.y0();
		boolean newRow = false;
		if (x + ww / 2 > limX || (x - ww / 2 < CROSSHAIR.x1() && x + ww / 2 > CROSSHAIR.x0() && y < CROSSHAIR.y1() && y + wh > CROSSHAIR.y0())) {
			if (x + ww / 2 > limX) {
				Rect rs = rowStart != null && placed.contains(rowStart) ? rectOf(v, rowStart, cfg) : null;
				x = (rs != null ? rs.x0() : pr.x0()) + ww / 2;
				y = Math.min(pr.y0(), rs != null ? rs.y0() : pr.y0()) - wh * 1.6;
				newRow = true;
				if (y < -limY) return null; // no more room below: start fresh somewhere else
			} else {
				x = CROSSHAIR.x1() + gap + ww / 2; // hop over the crosshair
			}
		}
		return new Hint(x, y, dist, newRow);
	}

	/** A rectangle on the screen in tangent units (x right, y up; 0,0 = crosshair). */
	private record Rect(double x0, double y0, double x1, double y1) {
		boolean overlaps(Rect o, double mx, double my) {
			return x0 - mx < o.x1 && o.x0 - mx < x1 && y0 - my < o.y1 && o.y0 - my < y1;
		}
	}

	/** What the camera sees: its position, axes and the tangents of half its field of view. */
	private record View(Vec3 pos, Vec3 look, Vec3 right, Vec3 up, double tanH, double tanV) {
		double[] project(Vec3 p) {
			Vec3 d = p.subtract(pos);
			double f = d.dot(look);
			if (f < 0.3) return null;
			return new double[] {d.dot(right) / f, d.dot(up) / f};
		}

		Vec3 direction(double x, double y) {
			return look.add(right.scale(x)).add(up.scale(y)).normalize();
		}
	}

	private static View view(Minecraft mc, CameraRenderState cam, Vec3 pos) {
		Vec3 look = cameraLook(cam);
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
		right = right.normalize();
		Vec3 up = right.cross(look).normalize();
		float vfov = mc.options.fov().get();
		float aspect = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
		double tanV = Math.tan(Math.toRadians(vfov / 2));
		return new View(pos, look, right, up, tanV * aspect, tanV);
	}

	/** Where a line standing at g (bottom middle), turned along lineRight, shows on screen; null if partly behind. */
	private static Rect rectOf(View v, Vec3 g, Vec3 lineRight, double w, double h) {
		double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			Vec3 c = g.add(lineRight.scale((i % 2 == 0 ? -0.5 : 0.5) * w)).add(0, i < 2 ? 0 : h, 0);
			double[] s = v.project(c);
			if (s == null) return null;
			x0 = Math.min(x0, s[0]);
			x1 = Math.max(x1, s[0]);
			y0 = Math.min(y0, s[1]);
			y1 = Math.max(y1, s[1]);
		}
		return new Rect(x0, y0, x1, y1);
	}

	private static Rect rectOf(View v, Placed p, HearNoDubConfig cfg) {
		if (p.anchor == null || p.right == null) return null;
		float u = p.unit(cfg);
		return rectOf(v, p.anchor, p.right, p.widthPx * u, (p.heightPx + 2) * u);
	}

	/** Screen rectangles of every line still standing (or still leaving), so new ones never cover them. */
	private static List<Rect> occupied(View v, HearNoDubConfig cfg) {
		List<Rect> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Placed p : placed) {
			if (p.leaveAt > 0 && now - p.leaveAt > exitLength(p)) continue;
			Rect r = rectOf(v, p, cfg);
			if (r != null) out.add(r);
		}
		return out;
	}

	/** Lines that land far away grow so they stay readable (but big letters don't grow further). */
	private static float autoSize(HearNoDubConfig cfg, double dist) {
		float grow = (float) Math.max(1, Math.pow(dist / 9.0, 0.9));
		float cap = Math.max(1, 1.6f / cfg.letterScale);
		return Math.min(grow, cap);
	}

	/** True when the whole line has room: nothing solid anywhere in its box. */
	private static boolean roomFor(ClientLevel level, Vec3 g, Vec3 lineRight, double w, double h) {
		for (int i = 0; i <= 4; i++) {
			Vec3 at = g.add(lineRight.scale((i / 4.0 - 0.5) * w));
			if (!level.noCollision(new AABB(at.x - 0.08, at.y + 0.03, at.z - 0.08, at.x + 0.08, at.y + h, at.z + 0.08))) return false;
		}
		return true;
	}

	private static final Rect CROSSHAIR = new Rect(-0.07, -0.05, 0.07, 0.05);

	/**
	 * Finds where a line (or word) lands. Rays go into the camera's view (third person too);
	 * a spot is only taken when the whole line is on screen now and a second from now, doesn't
	 * cover the crosshair or any line still standing, has room, and isn't on your walking path.
	 * Word mode aims at the hint: right after the previous word, like reading.
	 */
	private static Spot findSpot(Minecraft mc, HearNoDubConfig cfg, CameraRenderState cam, float width, float height, Hint hint) {
		ClientLevel level = mc.level;
		View view = view(mc, cam, cam.pos);
		Vec3 vel = motion(mc);
		double speed = vel.length();
		float maxDist = cfg.letterDistance + (float) Math.min(60, speed * 1.5); // moving: allow farther
		if (speed > 8) vel = vel.scale(8 / speed);
		Vec3 velDir = speed > 0.5 ? vel.normalize() : null;
		View future = view(mc, cam, cam.pos.add(vel.scale(1.0)));
		List<Rect> taken = occupied(view, cfg);
		double limX = view.tanH * 0.86, limY = view.tanV * 0.84;
		double spreadX = Math.min(limX, Math.tan(Math.toRadians(cfg.letterSpread)) * 1.2);

		Vec3 best = null;
		double bestScore = Double.NEGATIVE_INFINITY, bestPlants = 0;
		float bestSize = 1;
		for (int i = 0; i < 90; i++) {
			double sx, sy;
			if (hint != null && i < 60) {
				double spreadK = 1 + i / 15.0;
				sx = hint.x() + RANDOM.nextGaussian() * 0.035 * spreadK;
				sy = hint.y() + RANDOM.nextGaussian() * 0.025 * spreadK;
			} else if (velDir != null && speed > 1.5 && i % 3 == 0) {
				// ahead of where you're walking, off to the side of the path
				Vec3 side = new Vec3(-velDir.z, 0, velDir.x).scale((RANDOM.nextBoolean() ? 1 : -1) * (2 + RANDOM.nextDouble() * 4));
				Vec3 ahead = cam.pos.add(velDir.scale(6 + RANDOM.nextDouble() * 8)).add(side).add(0, -1.6, 0);
				double[] sc = view.project(ahead);
				if (sc == null) continue;
				sx = sc[0];
				sy = sc[1];
			} else {
				sx = (RANDOM.nextDouble() * 2 - 1) * spreadX;
				sy = (RANDOM.nextDouble() * 1.5 - 1.05) * limY; // mostly the lower half: that's where the ground is
			}
			Vec3 ground = groundAlong(level, cam.pos, view.direction(sx, sy), maxDist);
			if (ground == null) continue;
			float size = autoSize(cfg, ground.distanceTo(cam.pos));
			Score sc = scoreSpot(level, cfg, view, future, ground, width * size, height * size, maxDist, velDir, speed, taken, hint, limX, limY);
			if (sc.value() > bestScore) {
				bestScore = sc.value();
				best = ground;
				bestPlants = sc.plantTop();
				bestSize = size;
			}
		}
		if (best != null && bestScore > 2.5) {
			if (bestPlants > 0.05) return new Spot(best.add(0, bestPlants, 0), true, true, bestSize);
			return new Spot(best, true, false, bestSize);
		}

		// Nothing to stand on: float in the air (at the hint in word mode), with room and without covering anything
		for (int tries = 0; tries < 60; tries++) {
			double sx, sy, dist;
			if (hint != null && tries < 30) {
				sx = hint.x() + RANDOM.nextGaussian() * 0.02 * (1 + tries / 6.0);
				sy = hint.y() + RANDOM.nextGaussian() * 0.02 * (1 + tries / 6.0);
				dist = Math.max(4, Math.min(maxDist, hint.dist()));
			} else {
				sx = (RANDOM.nextBoolean() ? 1 : -1) * (0.15 + RANDOM.nextDouble() * Math.max(0.05, spreadX - 0.15));
				sy = (RANDOM.nextDouble() * 2 - 1) * limY * 0.5;
				dist = Math.min(5 + RANDOM.nextDouble() * 4, maxDist);
			}
			Vec3 dir = view.direction(sx, sy);
			var hit = clip(level, cam.pos, cam.pos.add(dir.scale(dist)));
			if (hit.getType() != HitResult.Type.MISS) dist = hit.getLocation().distanceTo(cam.pos) - 0.8;
			if (dist < Math.max(cfg.letterMinDistance, Math.max(width * 0.6 + 2.5, height * 5))) continue;
			Vec3 g = cam.pos.add(dir.scale(dist));
			if (hint == null) g = g.subtract(0, height / 2, 0); // the hint is a baseline; otherwise centre it
			Vec3 toCam = cam.pos.subtract(g);
			Vec3 lineRight = new Vec3(toCam.z, 0, -toCam.x).normalize();
			if (!roomFor(level, g, lineRight, width, height)) continue;
			Rect r = rectOf(view, g, lineRight, width, height);
			if (r == null || r.x0() < -limX || r.x1() > limX || r.y0() < -limY || r.y1() > limY) continue;
			if (r.overlaps(CROSSHAIR, 0, 0)) continue;
			boolean clash = false;
			for (Rect o : taken) {
				if (r.overlaps(o, 0.012, 0.012)) {
					clash = true;
					break;
				}
			}
			if (clash) continue;
			return new Spot(g, false, false, 1);
		}
		Vec3 dir = view.direction((RANDOM.nextBoolean() ? 1 : -1) * Math.min(limX * 0.7, 0.3), 0);
		return new Spot(cam.pos.add(dir.scale(5)).subtract(0, height / 2, 0), false, false, 1);
	}

	/** Where the camera looks (in third person that's not where the player looks). */
	private static Vec3 cameraLook(CameraRenderState cam) {
		Vector3f f = cam.orientation.transform(new Vector3f(0, 0, -1));
		return new Vec3(f.x, f.y, f.z).normalize();
	}

	/** Where a ray lands on the top of a block: directly, or by dropping down from where it hit. */
	private static Vec3 groundAlong(ClientLevel level, Vec3 eye, Vec3 dir, float maxDist) {
		BlockHitResult hit = clip(level, eye, eye.add(dir.scale(maxDist)));
		Vec3 from;
		if (hit.getType() == HitResult.Type.MISS) {
			from = eye.add(dir.scale(maxDist * (0.4 + RANDOM.nextFloat() * 0.55)));
		} else if (hit.getDirection() == Direction.UP) {
			return hit.getLocation();
		} else if (hit.getDirection() == Direction.DOWN) {
			return null;
		} else {
			from = hit.getLocation().add(hit.getDirection().getUnitVec3().scale(0.45));
		}
		double maxDrop = Math.max(3, Math.min(10, maxDist * 0.5));
		double drop = Math.max(1, from.y - (eye.y - maxDrop - 1.6));
		BlockHitResult down = clip(level, from, from.subtract(0, Math.min(14, drop), 0));
		if (down.getType() == HitResult.Type.MISS || down.getDirection() != Direction.UP) return null;
		return down.getLocation();
	}

	private static Score scoreSpot(ClientLevel level, HearNoDubConfig cfg, View view, View future, Vec3 g, float width, float height,
			float maxDist, Vec3 velDir, double speed, List<Rect> taken, Hint hint, double limX, double limY) {
		Score none = new Score(Double.NEGATIVE_INFINITY, 0);
		Vec3 cam = view.pos();
		Vec3 toCam = cam.subtract(g);
		double flat = Math.hypot(toCam.x, toCam.z);
		double dist = toCam.length();
		// never right in your face: farther for bigger letters
		if (flat < Math.max(cfg.letterMinDistance, Math.max(width * 0.6 + 2.5, height * 5)) || dist > maxDist * 1.3 + 2) return none;
		double below = cam.y - g.y;
		if (below > Math.max(4.5, maxDist * 0.55) + 1.6) return none;
		if (height / dist < 0.02) return none;
		Vec3 back = new Vec3(toCam.x / flat, 0, toCam.z / flat);
		Vec3 right = new Vec3(back.z, 0, -back.x);

		// fully on screen, clear of the crosshair, and not covering any line still standing
		Rect r = rectOf(view, g, right, width, height);
		if (r == null || r.x0() < -limX || r.x1() > limX || r.y0() < -limY || r.y1() > limY) return none;
		if (r.overlaps(CROSSHAIR, 0, 0)) return none;
		for (Rect o : taken) if (r.overlaps(o, 0.012, 0.012)) return none;

		int support = 0, seenLow = 0, seenTop = 0, plants = 0, samples = 0;
		double plantTop = 0;
		for (int i = 0; i <= 4; i++) {
			double t = (i / 4.0 - 0.5) * width;
			Vec3 at = g.add(right.scale(t));
			samples++;
			if (!level.noCollision(new AABB(at.x - 0.05, at.y - 0.2, at.z - 0.05, at.x + 0.05, at.y - 0.02, at.z + 0.05))) support++;
			double plant = plantHeight(level, at);
			if (plant > 0.05) {
				plants++;
				plantTop = Math.max(plantTop, plant);
			}
			if (!blockedOrPlant(level, cam, at.add(0, Math.max(0.12, plant + 0.05), 0))) seenLow++;
			if (!blockedOrPlant(level, cam, at.add(0, height * 0.9 + plant, 0))) seenTop++;
		}
		// room for every part of the line (no letter inside a block)
		if (!roomFor(level, g.add(0, plantTop, 0), right, width, height)) return none;
		if (seenTop < samples - 1) return none;
		Vec3 mid = g.add(0, height * 0.55 + plantTop, 0);
		if (blocked(level, cam, mid)) return none;
		double score = 4.0 * support / samples + 2.0 + 2.0 * seenLow / samples + 1.5 * seenTop / samples;
		score -= 2.2 * plants / samples;

		// a second from now, where you're heading: still on screen and visible
		if (speed > 1.0) {
			Rect fr = rectOf(future, g, right, width, height);
			if (fr == null || fr.x0() < -future.tanH() || fr.x1() > future.tanH() || fr.y0() < -future.tanV() || fr.y1() > future.tanV()) score -= 2.0;
			if (blocked(level, future.pos(), mid)) score -= 2.0;
		}
		if (velDir != null && speed > 1.0) {
			Vec3 rel = g.subtract(cam);
			double along = rel.x * velDir.x + rel.z * velDir.z;
			if (along > 0) {
				double sideways = Math.abs(rel.x * velDir.z - rel.z * velDir.x);
				if (sideways < width / 2 + 1.0) score -= 3.0; // on your path
				else if (along > 5) score += 1.0; // ahead of you, beside the path
			}
		}
		if (hint != null) {
			// word mode: right where the next word should go, at about the same distance
			double cx = (r.x0() + r.x1()) / 2;
			double off = Math.hypot(cx - hint.x(), r.y0() - hint.y());
			score -= off * 14;
			score -= Math.abs(dist - hint.dist()) / Math.max(2, hint.dist()) * 1.5;
		} else {
			double ideal = Math.max(cfg.letterMinDistance + 1, Math.min(maxDist * 0.6, width * 1.1 + 3));
			score -= Math.abs(dist - ideal) / Math.max(3.0, maxDist) * 1.5;
		}
		double drop = below - 1.6;
		if (drop > 2) score -= (drop - 2) * 0.3;
		if (drop < -1.5) score -= (-1.5 - drop) * 0.3;
		if (!level.getFluidState(BlockPos.containing(g.add(0, 0.1, 0))).isEmpty()) score -= 2;
		if (cfg.letterDirector) score += director(level, g, back);
		return new Score(score + RANDOM.nextDouble() * 0.3, plantTop);
	}

	/** How tall the plants (grass, flowers, snow layers...) growing at this spot are, 0 if none. */
	private static double plantHeight(ClientLevel level, Vec3 at) {
		BlockPos pos = BlockPos.containing(at.x, at.y + 0.05, at.z);
		double top = 0;
		for (int i = 0; i < 2; i++) {
			BlockPos p = pos.above(i);
			var state = level.getBlockState(p);
			if (state.isAir() || !state.getCollisionShape(level, p).isEmpty()) break;
			var shape = state.getShape(level, p);
			if (shape.isEmpty()) break;
			top = Math.max(top, p.getY() + shape.max(Direction.Axis.Y) - at.y);
			if (shape.max(Direction.Axis.Y) < 0.99) break;
		}
		return Math.min(1.6, top);
	}

	private static boolean blockedOrPlant(ClientLevel level, Vec3 from, Vec3 to) {
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceToSqr(to) > 0.04;
	}

	private static BlockHitResult clip(ClientLevel level, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
	}

	private static boolean blocked(ClientLevel level, Vec3 from, Vec3 to) {
		BlockHitResult hit = clip(level, from, to);
		return hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceToSqr(to) > 0.04;
	}

	// ================================================================ animation

	/** How a letter looks this frame (text pixels). */
	private record Pose(float dx, float dy, float dz, float sx, float sy, float alpha, boolean visible,
			float boost, float white, float dim) {}

	private static final Pose HIDDEN = new Pose(0, 0, 0, 0, 0, 0, false, 0, 0, 1);

	private static Pose letterPose(HearNoDubConfig cfg, Placed p, Glyph g, long now, long t, float pulse, float kick, float shimmer) {
		if (g.shattered) return HIDDEN;
		boolean ghosts = p.karaoke && p.spawn == LetterSpawn.LINE && cfg.lyricsReveal == LyricsReveal.DIMMED;
		float dx = 0, dy = 0, dz = 0, sx = 1, sy = 1, alpha = 1, white = 0, boost = 0, dim = 1;
		LetterAnimation anim = cfg.letterAnimation;
		boolean solid = p.grounded && !p.onPlants;
		if (!solid && anim == LetterAnimation.RISE) anim = LetterAnimation.PARTICLES;

		if (g.appearAt < 0 || now < g.appearAt) {
			if (!ghosts) return HIDDEN;
			float in = clamp01((now - p.spawnAt) / 300f); // not sung yet: a faint, darker letter waiting
			alpha = (0.18f + 0.6f * cfg.lyricsDimOpacity / 100f) * in;
			dim = 0.45f;
		} else {
			float tt = (now - g.appearAt) / 1000f;
			if (ghosts) {
				float k = clamp01(tt / 0.28f); // lights up with a little pop
				sx = sy = 1 + 0.28f * (1 - easeOut(k));
				white = 0.6f * (1 - k);
			} else {
				switch (anim) {
					case DROP -> {
						float k = clamp01(tt / 0.46f);
						dy = -(1 - bounce(k)) * 18f;
						alpha = clamp01(tt / 0.1f);
						if (cfg.letterLandingFx) {
							float land = 0.167f;
							if (tt < land) {
								sx = 0.9f; // stretched while falling
								sy = 1.12f;
							} else {
								float u = (tt - land) / 0.42f;
								if (u < 1) { // squash on impact, wobble back
									float d = (float) (Math.exp(-5 * u) * Math.cos(2.6 * Math.PI * u));
									sx = 1 + 0.26f * d;
									sy = 1 - 0.26f * d;
								}
							}
						}
					}
					case RISE -> dy = (1 - backOut(clamp01(tt / 0.42f))) * (p.heightPx + 3);
					case PARTICLES -> {
						sx = sy = backOut(clamp01(tt / 0.32f));
						alpha = clamp01(tt / 0.08f);
					}
					case FADE -> {
						float k = clamp01(tt / 0.3f);
						alpha = smooth(k);
						dy = (1 - smooth(k)) * 1.5f;
					}
				}
			}
		}

		// karaoke: the word being sung
		if (p.karaoke && p.leaveAt < 0 && g.word < p.ws.length && g.appearAt >= 0 && now >= g.appearAt) {
			long ws = p.ws[g.word], we = Math.max(ws + 1, p.we[g.word]);
			float env = t < ws ? 0 : t < ws + 90 ? (t - ws) / 90f : t < we ? 1 : Math.max(0, 1 - (t - we) / 320f);
			int li = g.letterInWord;
			if (env > 0) {
				switch (cfg.lyricsEffect) {
					case LIFT -> {
						dy -= 2.2f * env;
						sx *= 1 + 0.1f * env;
						sy *= 1 + 0.1f * env;
					}
					case BOUNCE -> dy -= 4.5f * (float) Math.sin(Math.PI * clamp01((t - ws) / 300f));
					case GLOW -> white += 0.4f * env;
					case WAVE -> dy -= 1.8f * env * (float) Math.sin(now * 0.012 - li * 0.8);
					case JELLY -> {
						float u = clamp01((t - ws) / 520f);
						float d = (float) (Math.exp(-5 * u) * Math.cos(3.2 * Math.PI * u));
						sx *= 1 + 0.24f * d;
						sy *= 1 - 0.2f * d;
					}
					default -> { }
				}
				if (we - ws > 1000) dy -= 1.3f * env * (float) Math.sin(now * 0.009 - li * 0.9); // held notes ripple
				if (cfg.lyricsEmphasis && shout(p, g)) {
					dx += (RANDOM.nextFloat() - 0.5f) * env;
					dy += (RANDOM.nextFloat() - 0.5f) * env;
					sx *= 1 + 0.1f * env;
					sy *= 1 + 0.1f * env;
				}
				boost = env;
				dy -= 2.2f * pulse * env; // on a beat, the word being sung hops a bit more
			}
		}
		if (pulse > 0) { // everything breathes with the beat
			sx *= 1 + 0.05f * pulse;
			sy *= 1 + 0.05f * pulse;
		}
		if (kick > 0) { // the bass drum: a thump like a speaker cone
			sy *= 1 + 0.1f * kick;
			sx *= 1 + 0.04f * kick;
		}
		if (shimmer > 0) white += 0.28f * shimmer * (boost > 0 ? 1 : 0.45f); // hi-hats and cymbals sparkle
		if (p.chorus) { // a shine sweeping across chorus lines
			float sweep = (float) Math.sin(now * 0.005 - g.index * 0.45);
			if (sweep > 0) white += 0.45f * (float) Math.pow(sweep, 10);
		}

		if (p.leaveAt > 0 && now >= p.leaveAt) {
			float e = clamp01((now - p.leaveAt - Math.min(g.index * 16L, 260)) / 380f);
			switch (anim) {
				case DROP, RISE -> {
					if (solid) dy += easeIn(e) * (p.heightPx + 4); // sinks into the block
					else alpha *= 1 - e;
					if (e > 0.85f) alpha *= (1 - e) / 0.15f;
				}
				case PARTICLES -> {
					sx *= 1 - easeIn(e);
					sy *= 1 - easeIn(e);
					alpha *= 1 - e * e;
				}
				case FADE -> {
					alpha *= 1 - smooth(e);
					dy -= smooth(e) * 1.5f;
				}
			}
		}
		if (!p.grounded) dy += (float) Math.sin((now - p.spawnAt) / 600.0 + p.key % 7) * 0.6f; // gentle float
		boolean visible = alpha > 0.01f && sx > 0.01f && sy > 0.01f;
		return new Pose(dx, dy, dz, sx, sy, alpha, visible, boost, white, dim);
	}

	private static boolean shout(Placed p, Glyph g) {
		int letters = 0;
		boolean bang = false, lower = false;
		for (Glyph o : p.glyphs) {
			if (o.word != g.word || o.cp == ' ') continue;
			if (o.cp == '!') bang = true;
			if (Character.isLetter(o.cp)) {
				if (!Character.isUpperCase(o.cp)) lower = true;
				letters++;
			}
		}
		return bang || !lower && letters >= 2;
	}

	private static float clamp01(float v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	private static float smooth(float k) {
		return k * k * (3 - 2 * k);
	}

	private static float easeIn(float k) {
		return k * k * k;
	}

	private static float easeOut(float k) {
		float x = 1 - k;
		return 1 - x * x * x;
	}

	private static float backOut(float k) {
		float c1 = 1.70158f, c3 = c1 + 1;
		float x = k - 1;
		return 1 + c3 * x * x * x + c1 * x * x;
	}

	private static float bounce(float k) {
		float n1 = 7.5625f, d1 = 2.75f;
		if (k < 1 / d1) return n1 * k * k;
		if (k < 2 / d1) { k -= 1.5f / d1; return n1 * k * k + 0.75f; }
		if (k < 2.5 / d1) { k -= 2.25f / d1; return n1 * k * k + 0.9375f; }
		k -= 2.625f / d1;
		return n1 * k * k + 0.984375f;
	}

	// ================================================================ helpers

	/** World position of a letter's middle. */
	private static Vec3 glyphWorld(Placed p, Glyph g, float u) {
		float lx = g.x + g.width / 2f - p.widthPx / 2f;
		float ly = g.lineTop + 3.5f;
		return p.anchor.add(p.right.scale(lx * u)).add(0, -ly * u, 0);
	}

	// ================================================================ materials

	/** The block a letter is made of (for particles and sounds), or null for plain colour. */
	private static BlockState materialBlock(Placed p, Glyph g) {
		return switch (materialOf(p, g)) {
			case GROUND -> p.ground != null && !p.ground.isAir() ? p.ground : Blocks.STONE.defaultBlockState();
			case GOLD -> Blocks.GOLD_BLOCK.defaultBlockState();
			case DIAMOND -> Blocks.DIAMOND_BLOCK.defaultBlockState();
			case AMETHYST -> Blocks.AMETHYST_BLOCK.defaultBlockState();
			case ICE -> Blocks.PACKED_ICE.defaultBlockState();
			default -> null;
		};
	}

	private static LetterMaterial materialOf(Placed p, Glyph g) {
		return p.material;
	}

	private static TextureAtlasSprite sprite(Minecraft mc, Placed p, Glyph g) {
		LetterMaterial m = materialOf(p, g);
		String name = switch (m) {
			case GOLD -> "block/gold_block";
			case DIAMOND -> "block/diamond_block";
			case AMETHYST -> "block/amethyst_block";
			case ICE -> "block/ice";
			case GROUND -> null;
			default -> "";
		};
		if (name != null && name.isEmpty()) return null; // plain colour
		try {
			TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(TextureAtlas.LOCATION_BLOCKS);
			if (name != null) return atlas.getSprite(Identifier.withDefaultNamespace(name));
			BlockState st = p.ground != null && !p.ground.isAir() ? p.ground : Blocks.STONE.defaultBlockState();
			String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
			TextureAtlasSprite top = atlas.getSprite(Identifier.withDefaultNamespace("block/" + path + "_top"));
			if (top != null && top != atlas.missingSprite()) return top;
			return mc.getModelManager().getBlockStateModelSet().getParticleMaterial(st).sprite();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Grass and leaves get their biome colour. */
	private static int tint(Minecraft mc, Placed p, Glyph g) {
		if (materialOf(p, g) != LetterMaterial.GROUND || p.ground == null || mc.level == null) return 0xFFFFFF;
		try {
			BlockTintSource src = mc.getBlockColors().getTintSource(p.ground, 0);
			if (src == null) return 0xFFFFFF;
			return src.colorInWorld(p.ground, mc.level, p.groundPos) & 0xFFFFFF;
		} catch (RuntimeException e) {
			return 0xFFFFFF;
		}
	}

	private static int colorOf(Placed p, Glyph g, long now) {
		if (materialOf(p, g) == LetterMaterial.RAINBOW) {
			float hue = (g.index * 0.06f + now * 0.00018f) % 1f;
			return java.awt.Color.HSBtoRGB(hue, 0.62f, 1f) & 0xFFFFFF;
		}
		return g.color;
	}

	// ================================================================ drawing

	private static void draw(LevelRenderContext ctx, Minecraft mc, HearNoDubConfig cfg, CameraRenderState cam,
			SubStyle style, Placed p, long now, long t) {
		SubmitNodeCollector out = ctx.submitNodeCollector();
		PoseStack pose = ctx.poseStack();
		float u = p.unit(cfg);
		float half = p.widthPx / 2f;
		LetterStyle ls = p.style;
		boolean neonish = ls == LetterStyle.NEON || ls == LetterStyle.HOLOGRAM;
		boolean glowing = cfg.letterGlow || ls == LetterStyle.NEON;
		boolean shaders = BlockGlow.shaderPackInUse();
		boolean react = cfg.letterAudioReact && AudioLevel.active();
		float pulse = react ? AudioLevel.pulse() : 0;
		boolean bands = cfg.letterBands && cfg.letterAudioReact && AudioLevel.bands();
		float kick = bands ? AudioLevel.kick() : 0, shimmer = bands ? AudioLevel.shimmer() : 0;

		int light;
		if (glowing || neonish) {
			light = FULL_BRIGHT;
		} else {
			int world = LightCoordsUtil.getLightCoords(mc.level, BlockPos.containing(p.anchor.add(0, 0.3, 0)));
			light = LightCoordsUtil.max(world, LightCoordsUtil.pack(9, 0));
		}

		List<Glyph> glyphs = p.glyphs;
		Pose[] poses = new Pose[glyphs.size()];
		float presence = 0;
		int letters = 0;
		for (int i = 0; i < glyphs.size(); i++) {
			Glyph g = glyphs.get(i);
			poses[i] = letterPose(cfg, p, g, now, t, pulse, kick, shimmer);
			if (g.cp != ' ') {
				letters++;
				if (poses[i].visible && poses[i].dim >= 1) presence += poses[i].alpha * Math.min(1, poses[i].sy);
			}
		}
		if (letters > 0) presence /= letters;
		effects(mc, cfg, p, now, t, u);

		pose.pushPose();
		pose.translate(p.anchor.x - cam.pos.x, p.anchor.y - cam.pos.y, p.anchor.z - cam.pos.z);
		pose.rotate(p.rotation);
		pose.scale(u, -u, u);

		// Soft shadows on the block under each letter
		if (cfg.letterShadow && p.grounded && !p.onPlants && presence > 0.01f) {
			float shadowStrength = ls == LetterStyle.GLASS || neonish ? 0.25f : 0.5f;
			out.submitCustomGeometry(pose, RenderTypes.entityTranslucent(GlowTexture.get()), (pp, vc) -> {
				for (int i = 0; i < glyphs.size(); i++) {
					Glyph g = glyphs.get(i);
					Pose lp = poses[i];
					if (g.cp == ' ' || !lp.visible || g.lineTop < -7.5f) continue;
					float lift = Math.max(0, -lp.dy), sink = Math.max(0, lp.dy);
					float k = Math.max(0, 1 - lift / 20f) * Math.max(0, 1 - sink / (p.heightPx + 4)) * lp.alpha * lp.dim;
					if (k <= 0.01f) continue;
					float cx = g.x + g.width / 2f - half + lp.dx;
					float hw = (g.width * 0.75f + 2.5f) * (0.6f + 0.4f * Math.max(0, 1 - lift / 20f)) * lp.sx;
					float depth = 5.5f;
					float cz = lp.dz;
					int c = SubStyle.fade(0xFF000000, shadowStrength * k);
					float y = -0.25f;
					shadowVertex(vc, pp, cx - hw, y, cz - depth, 0, 0, c);
					shadowVertex(vc, pp, cx + hw, y, cz - depth, 1, 0, c);
					shadowVertex(vc, pp, cx + hw, y, cz + depth, 1, 1, c);
					shadowVertex(vc, pp, cx - hw, y, cz + depth, 0, 1, c);
				}
			});
		}

		final float wetF = p.wet, snowF = p.snow;
		boolean mcFlat = ls == LetterStyle.FLAT && p.font == LetterFont.MINECRAFT
				&& (p.material == LetterMaterial.COLOR || p.material == LetterMaterial.RAINBOW);
		if (!mcFlat) {
			boolean emissive = (glowing || neonish) && BlockGlow.emissiveGlow();
			final int lightF = light;
			final int outlineColor = style.outline() ? style.outlineColor() : 0xFF101010;
			RenderType plain = emissive ? RenderTypes.entityTranslucentEmissive(GlyphBitmaps.white()) : RenderTypes.entityTranslucent(GlyphBitmaps.white());
			out.submitCustomGeometry(pose, plain, (pp, vc) -> {
				weatherWet = wetF; // geometry is built later, so set the weather here
				weatherSnow = snowF;
				for (int i = 0; i < glyphs.size(); i++) {
					Glyph g = glyphs.get(i);
					Pose lp = poses[i];
					if (g.bitmap == null || !lp.visible) continue;
					if (!neonish && sprite(mc, p, g) != null) continue;
					float[] mesh = LetterMesh.get(g.bitmap, ls);
					int color = colorOf(p, g, now);
					if (ls == LetterStyle.HOLOGRAM) {
						color = mixRgb(color, 0x7FFFFF, 0.45f);
						float flicker = 0.82f + RANDOM.nextFloat() * 0.18f;
						float glitch = RANDOM.nextInt(40) == 0 ? (RANDOM.nextFloat() - 0.5f) * 2f : 0;
						emit(vc, pp, g, withAlpha(lp, lp.alpha * flicker), half - glitch, lightF, mesh, outlineColor, color, null, 0, false);
						emit(vc, pp, g, withAlpha(lp, lp.alpha * 0.25f), half + 0.35f, lightF, mesh, outlineColor, 0xFF4060, null, 0, false);
						emit(vc, pp, g, withAlpha(lp, lp.alpha * 0.25f), half - 0.35f, lightF, mesh, outlineColor, 0x4080FF, null, 0, false);
						continue;
					}
					emit(vc, pp, g, lp, half, lightF, mesh, outlineColor, color, null, 0, ls == LetterStyle.NEON);
					if (g.style.isBold() && p.font == LetterFont.MINECRAFT) emit(vc, pp, g, lp, half - 1, lightF, mesh, outlineColor, color, null, 0, false);
				}
			});
			if (!neonish) { // letters made of blocks (one atlas for all of them)
				RenderType blocks = emissive ? RenderTypes.entityTranslucentEmissive(TextureAtlas.LOCATION_BLOCKS)
						: RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
				out.submitCustomGeometry(pose, blocks, (pp, vc) -> {
					weatherWet = wetF;
					weatherSnow = snowF;
					for (int i = 0; i < glyphs.size(); i++) {
						Glyph g = glyphs.get(i);
						Pose lp = poses[i];
						if (g.bitmap == null || !lp.visible) continue;
						TextureAtlasSprite sp = sprite(mc, p, g);
						if (sp == null) continue;
						Pose mp = materialOf(p, g) == LetterMaterial.ICE ? withAlpha(lp, lp.alpha * 0.8f) : lp;
						emit(vc, pp, g, mp, half, lightF, LetterMesh.get(g.bitmap, ls), outlineColor, tint(mc, p, g), sp, g.index, false);
					}
				});
			}
		}
		// Flat Minecraft text (and letters no font here has)
		Font.DisplayMode mode = Font.DisplayMode.NORMAL;
		for (int i = 0; i < glyphs.size(); i++) {
			Glyph g = glyphs.get(i);
			Pose lp = poses[i];
			if (g.cp == ' ' || !lp.visible) continue;
			if (!mcFlat && g.bitmap != null) continue;
			pose.pushPose();
			float pivotX = g.x + g.width / 2f - half, pivotY = g.lineTop + 7f;
			pose.translate(pivotX + lp.dx, pivotY + lp.dy, lp.dz);
			pose.scale(lp.sx, lp.sy, 1);
			pose.translate(-g.width / 2f, -7f, 0);
			int outline = style.outline() ? style.outlineColor() : 0xFF000000;
			int rgb = brighten(colorOf(p, g, now), Math.max(lp.boost, lp.white));
			if (lp.dim < 1) rgb = scaleRgb(rgb, lp.dim);
			out.submitText(pose, 0, 0, g.seq, false, mode, light, SubStyle.fade(0xFF000000 | rgb, lp.alpha), 0,
					SubStyle.fade(outline, lp.alpha * 0.9f));
			pose.popPose();
		}
		pose.popPose();

		// Light on the blocks around the letters
		if (glowing && presence > 0.01f) {
			float strength = cfg.letterGlowStrength / 100f * 1.8f * presence;
			if (react) strength *= 0.55f + 0.45f * Math.min(2f, AudioLevel.relative()) + 0.6f * pulse;
			if (p.flashAt > 0 && now - p.flashAt < 500) strength += 3f * (1 - (now - p.flashAt) / 500f);
			if (ls == LetterStyle.NEON) strength *= 1.4f;
			Vec3 center = p.anchor.add(0, p.heightPx * u * 0.5, 0).add(p.back.scale(0.2));
			float reach = Math.max(2.5f, p.widthPx * u * 0.55f + 2.2f);
			p.glow.draw(ctx, cam, center, null, reach, p.glowR * strength, p.glowG * strength, p.glowB * strength, 1000);
		}
	}

	private static Pose withAlpha(Pose lp, float a) {
		return new Pose(lp.dx, lp.dy, lp.dz, lp.sx, lp.sy, a, lp.visible, lp.boost, lp.white, lp.dim);
	}

	private static void shadowVertex(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float u, float v, int color) {
		vc.addVertex(p, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(FULL_BRIGHT).setNormal(p, 0f, -1f, 0f);
	}

	/**
	 * Sends a cached letter mesh with this frame's animation: scale and squash around the
	 * letter's bottom middle, then the offsets. With a sprite, faces are textured like the
	 * block (one block pixel per text pixel). Rain darkens and shines them, snow whitens the tops.
	 */
	private static float weatherWet, weatherSnow;

	private static void emit(VertexConsumer vc, PoseStack.Pose p, Glyph g, Pose lp, float half, int light, float[] mesh,
			int outlineColor, int rgb, TextureAtlasSprite sprite, int texOffset, boolean neon) {
		float wet = weatherWet, snowK = weatherSnow;
		float px = g.x + g.width / 2f - half, py = g.lineTop + 7f;
		float gx = g.x - half, gy = g.lineTop;
		float boost = Math.max(lp.boost, 0);
		int base = boost > 0 ? brighten(rgb, boost) : rgb;
		int outline = SubStyle.fade(outlineColor, lp.alpha);
		float offU = (texOffset * 5) % 6, offV = (texOffset * 3) % 5;
		for (int o = 0; o < mesh.length; o += LetterMesh.QUAD) {
			float shade = mesh[o + 15];
			float whiteMix = Math.min(1, mesh[o + 16] + lp.white);
			float qa = mesh[o + 17];
			int color;
			if (shade < 0) {
				color = outline;
			} else {
				float sh = shade * (1 + 0.25f * boost) * lp.dim;
				if (wet > 0) sh *= 1 - 0.2f * wet; // soaked: darker
				int c = scaleRgb(base, Math.min(sprite != null ? 1.2f : 1.25f, sh));
				if (wet > 0 && mesh[o + 14] > 0.5f) whiteMix = Math.min(1, whiteMix + 0.12f * wet); // and shiny
				if (whiteMix > 0) c = mixRgb(c, 0xFFFFFF, neon ? whiteMix : whiteMix * 0.8f);
				if (snowK > 0 && mesh[o + 13] < -0.5f) c = mixRgb(c, 0xF4F8FF, 0.85f * snowK); // snow on the tops
				int a = Math.max(0, Math.min(255, Math.round(lp.alpha * qa * 255)));
				color = a << 24 | c;
			}
			float nx = mesh[o + 12], ny = mesh[o + 13], nz = mesh[o + 14];
			for (int k = 0; k < 4; k++) {
				int q = o + k * 3;
				float lx = mesh[q], ly = mesh[q + 1], lz = mesh[q + 2];
				float x = px + (gx + lx - px) * lp.sx;
				float y = py + (gy + ly - py) * lp.sy;
				float z = lz * lp.sx;
				float uu = 0.5f, vv = 0.5f;
				if (sprite != null) {
					float a, b;
					if (Math.abs(nz) >= Math.abs(nx) && Math.abs(nz) >= Math.abs(ny)) {
						a = lx - g.bitmap.left();
						b = ly - g.bitmap.top();
					} else if (Math.abs(nx) >= Math.abs(ny)) {
						a = -lz;
						b = ly - g.bitmap.top();
					} else {
						a = lx - g.bitmap.left();
						b = -lz;
					}
					uu = sprite.getU(clamp01((offU + a) / 16f));
					vv = sprite.getV(clamp01((offV + b) / 16f));
				}
				vc.addVertex(p, x + lp.dx, y + lp.dy, z + lp.dz).setColor(color).setUv(uu, vv)
						.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
			}
		}
	}

	private static int scaleRgb(int rgb, float k) {
		int r = Math.min(255, Math.round(((rgb >> 16) & 255) * k));
		int g = Math.min(255, Math.round(((rgb >> 8) & 255) * k));
		int b = Math.min(255, Math.round((rgb & 255) * k));
		return r << 16 | g << 8 | b;
	}

	private static int mixRgb(int a, int b, float k) {
		int r = Math.round(((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
		int g = Math.round(((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
		int bl = Math.round((a & 255) * (1 - k) + (b & 255) * k);
		return r << 16 | g << 8 | bl;
	}

	/** Mixes a colour toward white for the word being sung. */
	private static int brighten(int rgb, float k) {
		return k <= 0 ? rgb : mixRgb(rgb, 0xFFFFFF, Math.min(1, 0.22f * k));
	}

	// ================================================================ effects: dust, sounds, sparkles, fireworks

	private static void sound(Minecraft mc, HearNoDubConfig cfg, SoundEvent s, Vec3 at, float volume, float pitch) {
		if (s == null || mc.level == null || cfg.letterSoundVolume <= 0) return;
		long now = System.currentTimeMillis();
		if (now - lastSound < 45) return;
		lastSound = now;
		mc.level.playLocalSound(at.x, at.y, at.z, s, SoundSource.AMBIENT, volume * cfg.letterSoundVolume / 100f, pitch, false);
	}

	private static void dust(Minecraft mc, Placed p, Vec3 at, int n) {
		if (mc.level == null || p.ground == null || p.ground.isAir()) return;
		for (int i = 0; i < n; i++) {
			mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, p.ground), at.x + (RANDOM.nextDouble() - 0.5) * 0.2,
					at.y + 0.02, at.z + (RANDOM.nextDouble() - 0.5) * 0.2, (RANDOM.nextDouble() - 0.5) * 0.1, 0.05, (RANDOM.nextDouble() - 0.5) * 0.1);
		}
	}

	private static void effects(Minecraft mc, HearNoDubConfig cfg, Placed p, long now, long t, float u) {
		if (mc.level == null || mc.isPaused()) return;
		boolean solid = p.grounded && !p.onPlants;
		LetterAnimation anim = cfg.letterAnimation;
		boolean ghostsOn = p.karaoke && p.spawn == LetterSpawn.LINE && cfg.lyricsReveal == LyricsReveal.DIMMED;
		for (Glyph g : p.glyphs) {
			if (g.cp == ' ' || g.appearAt < 0 || now < g.appearAt || g.shattered) continue;
			Vec3 at = glyphWorld(p, g, u).add(p.back.scale(0.05));
			if (!g.landed) { // landing: dust, a bump and a ring of dust for each word
				long landMs = anim == LetterAnimation.DROP ? 167 : 40;
				if (now - g.appearAt >= landMs) {
					g.landed = true;
					if (cfg.letterLandingFx && solid && !ghostsOn && (anim == LetterAnimation.DROP || anim == LetterAnimation.RISE)) {
						Vec3 foot = p.anchor.add(p.right.scale((g.x + g.width / 2f - p.widthPx / 2f) * u));
						dust(mc, p, foot, 2);
						if (g.letterInWord == 0) {
							if (p.ground != null) sound(mc, cfg, p.ground.getSoundType().getStepSound(), foot, 0.35f, 0.9f + RANDOM.nextFloat() * 0.4f);
							ring(mc, foot, p);
						}
					}
				}
			}
			if (anim == LetterAnimation.PARTICLES && !g.sparked && !ghostsOn) {
				g.sparked = true;
				for (int k = 0; k < 2 + RANDOM.nextInt(2); k++) {
					mc.level.addParticle(new DustParticleOptions(colorOf(p, g, now), 0.55f + RANDOM.nextFloat() * 0.3f),
							at.x + (RANDOM.nextDouble() - 0.5) * g.width * u, at.y + (RANDOM.nextDouble() - 0.5) * 0.3, at.z,
							(RANDOM.nextDouble() - 0.5) * 0.06, RANDOM.nextDouble() * 0.05 + 0.01, (RANDOM.nextDouble() - 0.5) * 0.06);
				}
			}
			if (p.leaveAt > 0 && anim == LetterAnimation.PARTICLES && !g.puffed && now - p.leaveAt >= Math.min(g.index * 16L, 260) + 120) {
				g.puffed = true;
				mc.level.addParticle(ParticleTypes.POOF, at.x, at.y, at.z, (RANDOM.nextDouble() - 0.5) * 0.02, 0.01, (RANDOM.nextDouble() - 0.5) * 0.02);
			}
			if (cfg.letterLandingFx && p.karaoke && p.leaveAt < 0 && g.word < p.ws.length) { // held notes sparkle
				long ws = p.ws[g.word], we = p.we[g.word];
				if (we - ws > 900 && t >= ws && t < we && RANDOM.nextInt(28) == 0) {
					mc.level.addParticle(ParticleTypes.END_ROD, at.x + (RANDOM.nextDouble() - 0.5) * 0.3, at.y + 0.1,
							at.z + (RANDOM.nextDouble() - 0.5) * 0.3, 0, 0.03, 0);
				}
			}
		}
		if (cfg.letterAudioReact && cfg.letterLandingFx && p.leaveAt < 0 && AudioLevel.active() && !p.glyphs.isEmpty()) {
			long beats = AudioLevel.beats();
			if (beats != lastBeats && AudioLevel.pulse() > 0.6f) { // beats: a little sparkle
				lastBeats = beats;
				Glyph g = p.glyphs.get(RANDOM.nextInt(p.glyphs.size()));
				if (g.appearAt >= 0 && g.cp != ' ' && !g.shattered) {
					Vec3 at = glyphWorld(p, g, u);
					mc.level.addParticle(ParticleTypes.END_ROD, at.x, at.y + 0.15, at.z, 0, 0.04, 0);
				}
			}
		}
		if (p.finale && cfg.letterLandingFx && p.leaveAt > 0 && now >= p.leaveAt && !p.exploded) { // the end of the song: fireworks
			p.exploded = true;
			p.flashAt = now;
			for (Glyph g : p.glyphs) {
				if (g.cp == ' ' || g.shattered) continue;
				Vec3 at = glyphWorld(p, g, u);
				for (int k = 0; k < 4; k++) {
					Vec3 v = new Vec3(RANDOM.nextGaussian(), RANDOM.nextGaussian() + 0.6, RANDOM.nextGaussian()).normalize().scale(0.12 + RANDOM.nextDouble() * 0.12);
					mc.level.addParticle(ParticleTypes.FIREWORK, at.x, at.y, at.z, v.x, v.y, v.z);
				}
				mc.level.addParticle(new DustParticleOptions(colorOf(p, g, now), 1.4f), at.x, at.y, at.z, 0, 0.05, 0);
				g.shattered = true;
			}
			lastSound = 0;
			sound(mc, cfg, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, p.anchor, 1.0f, 1.0f);
			lastSound = 0;
			sound(mc, cfg, SoundEvents.FIREWORK_ROCKET_TWINKLE, p.anchor.add(0, 1, 0), 0.7f, 1.0f);
		}
	}

	/** A ring of dust spreading out from where a word landed. */
	private static void ring(Minecraft mc, Vec3 at, Placed p) {
		int color = p.ground != null ? 0xE8E2D6 : 0xFFFFFF;
		for (int i = 0; i < 12; i++) {
			double a = i / 12.0 * Math.PI * 2;
			double vx = Math.cos(a) * 0.11, vz = Math.sin(a) * 0.11;
			mc.level.addParticle(new DustParticleOptions(color, 0.55f), at.x + vx, at.y + 0.04, at.z + vz, vx, 0.004, vz);
		}
	}
}
