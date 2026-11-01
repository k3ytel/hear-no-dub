package dev.k3ytel.hearnodub.client.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.k3ytel.hearnodub.client.HearNoDubClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/** The GPU texture the video frames are uploaded to. Render thread only. */
public final class VideoTexture {
	public static final Identifier ID = HearNoDubClient.id("video");

	private static DynamicTexture texture;
	private static int width, height;
	private static int lastSeq = -1;
	private static Method memByteBuffer;
	private static boolean memLookupFailed;

	private VideoTexture() {}

	/** Uploads the newest frame if there is one. Returns true when a frame is available. */
	public static boolean update() {
		int seq = VideoCapture.get().readFrame(lastSeq, VideoTexture::copy);
		if (seq != lastSeq && texture != null) {
			lastSeq = seq;
			texture.upload();
		}
		return texture != null && lastSeq != -1;
	}

	public static DynamicTexture texture() {
		return texture;
	}

	public static int width() {
		return width;
	}

	public static int height() {
		return height;
	}

	/** Frees the texture (video turned off). */
	public static void release() {
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(ID);
			texture = null;
		}
		lastSeq = -1;
	}

	private static boolean copy(int w, int h, ByteBuffer rgba) {
		if (texture == null || w != width || h != height) {
			release();
			texture = new SmoothTexture(w, h);
			Minecraft.getInstance().getTextureManager().register(ID, texture);
			width = w;
			height = h;
		}
		NativeImage img = texture.getPixels();
		if (img == null) return false;
		ByteBuffer dst = nativeView(img.getPointer(), w * h * 4);
		if (dst == null) return false;
		dst.put(rgba.duplicate());
		return true;
	}

	/** A dynamic texture that's scaled smoothly (linear filtering) instead of in blocky pixels. */
	private static final class SmoothTexture extends DynamicTexture {
		SmoothTexture(int w, int h) {
			super(() -> "Hear No Dub video", w, h, false);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}

	/** A ByteBuffer over the image's native memory (LWJGL's MemoryUtil, looked up at runtime). */
	private static ByteBuffer nativeView(long address, int size) {
		if (address == 0) return null;
		try {
			if (memByteBuffer == null && !memLookupFailed) {
				memByteBuffer = Class.forName("org.lwjgl.system.MemoryUtil").getMethod("memByteBuffer", long.class, int.class);
			}
			if (memByteBuffer != null) return (ByteBuffer) memByteBuffer.invoke(null, address, size);
		} catch (ReflectiveOperationException | RuntimeException e) {
			memLookupFailed = true;
			HearNoDubClient.LOGGER.error("Can't write video frames into the texture", e);
		}
		return null;
	}
}
