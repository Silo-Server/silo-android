package org.siloserver.silo.libass;

import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.NoSampleRenderer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.ui.SubtitleView;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import io.github.peerless2012.ass.Ass;
import io.github.peerless2012.ass.AssRender;
import io.github.peerless2012.ass.media.AssHandler;
import io.github.peerless2012.ass.media.AssHandlerConfig;
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor;
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory;
import io.github.peerless2012.ass.media.type.AssRenderType;
import io.github.peerless2012.ass.media.widget.AssSubtitleView;

/**
 * Java boundary around ass-media/libass.
 *
 * ass-media is Kotlin 2.2 bytecode while Silo currently compiles Kotlin 2.1.
 * Keeping all ass-media symbols private to this Java module avoids forcing a
 * repo-wide compiler and KSP upgrade. Callers see only Media3 and Java types.
 */
@UnstableApi
public final class LibassBridge {
    // Media3 offsets renderer positions by 10^12us. ass-media's own renderer
    // subtracts the same value before asking libass for the active frame.
    private static final long MEDIA3_RENDERER_POSITION_OFFSET_US = 1_000_000_000_000L;
    private static final List<String> PACKAGED_ABIS = Arrays.asList(
            "arm64-v8a", "armeabi-v7a", "x86", "x86_64"
    );

    private final boolean renderingSupported;
    private final boolean embeddedFontsSupported;
    private final AssHandler handler;
    private final SubtitleParser.Factory parserFactory;
    private WeakReference<AssSubtitleView> overlayRef = new WeakReference<>(null);
    private ExoPlayer initializedPlayer;

    private final Player.Listener frameSizeSyncListener = new Player.Listener() {
        @Override
        public void onTracksChanged(Tracks tracks) {
            syncOverlayFrameSizeLater();
        }
    };

    public LibassBridge(boolean preferOpenGl) {
        renderingSupported = probeNativeRuntime();
        embeddedFontsSupported = renderingSupported && probeMatroskaIntegration();
        if (renderingSupported) {
            AssRenderType renderType = preferOpenGl
                    ? AssRenderType.OVERLAY_OPEN_GL
                    : AssRenderType.OVERLAY_CANVAS;
            handler = new AssHandler(renderType, new AssHandlerConfig());
            parserFactory = buildParserFactory();
        } else {
            handler = null;
            parserFactory = new DefaultSubtitleParserFactory();
        }
    }

    public boolean isRenderingSupported() {
        return renderingSupported;
    }

    public boolean isEmbeddedFontsSupported() {
        return embeddedFontsSupported;
    }

    public SubtitleParser.Factory getParserFactory() {
        return parserFactory;
    }

    private SubtitleParser.Factory buildParserFactory() {
        AssSubtitleParserFactory assFactory = new AssSubtitleParserFactory(handler);
        if (embeddedFontsSupported) return assFactory;

        // The ass-media Matroska extractor supplies both timed dialogue packets
        // and font attachments. If its Media3 reflection probe fails, do not
        // hand embedded MKV ASS to its no-op overlay parser or subtitles would
        // disappear. Preserve Media3's plain-text fallback while retaining
        // native libass rendering for independent sidecar ASS files.
        DefaultSubtitleParserFactory media3Factory = new DefaultSubtitleParserFactory();
        return new SubtitleParser.Factory() {
            @Override
            public boolean supportsFormat(Format format) {
                return assFactory.supportsFormat(format);
            }

            @Override
            public int getCueReplacementBehavior(Format format) {
                return assFactory.getCueReplacementBehavior(format);
            }

            @Override
            public SubtitleParser create(Format format) {
                boolean unsupportedEmbeddedAss = MimeTypes.TEXT_SSA.equals(format.sampleMimeType)
                        && MimeTypes.VIDEO_MATROSKA.equals(format.containerMimeType);
                return unsupportedEmbeddedAss
                        ? media3Factory.create(format)
                        : assFactory.create(format);
            }
        };
    }

    /**
     * Replaces Media3's Matroska extractor while retaining its position in the
     * extractor list. The replacement captures MKV font attachments and routes
     * ASS packets to the same libass handler as sidecar subtitles.
     */
    public ExtractorsFactory wrapExtractors(
            ExtractorsFactory delegate,
            SubtitleParser.Factory combinedParserFactory
    ) {
        if (!embeddedFontsSupported) return delegate;
        return new ExtractorsFactory() {
            @Override
            public Extractor[] createExtractors() {
                return replaceMatroska(delegate.createExtractors(), combinedParserFactory);
            }

            @Override
            public Extractor[] createExtractors(
                    Uri uri,
                    Map<String, List<String>> responseHeaders
            ) {
                return replaceMatroska(
                        delegate.createExtractors(uri, responseHeaders),
                        combinedParserFactory
                );
            }
        };
    }

    /** Adds a clock-only renderer that drives libass without consuming samples. */
    public RenderersFactory wrapRenderers(
            RenderersFactory delegate,
            LongSupplier subtitleOffsetUs
    ) {
        if (!renderingSupported) return delegate;
        return new RenderersFactory() {
            @Override
            public Renderer[] createRenderers(
                    Handler eventHandler,
                    VideoRendererEventListener videoListener,
                    AudioRendererEventListener audioListener,
                    TextOutput textOutput,
                    MetadataOutput metadataOutput
            ) {
                Renderer[] base = delegate.createRenderers(
                        eventHandler,
                        videoListener,
                        audioListener,
                        textOutput,
                        metadataOutput
                );
                Renderer[] result = Arrays.copyOf(base, base.length + 1);
                result[base.length] = new SiloLibassRenderer(handler, subtitleOffsetUs);
                return result;
            }

            @Override
            public Renderer createSecondaryRenderer(
                    Renderer renderer,
                    Handler eventHandler,
                    VideoRendererEventListener videoListener,
                    AudioRendererEventListener audioListener,
                    TextOutput textOutput,
                    MetadataOutput metadataOutput
            ) {
                return delegate.createSecondaryRenderer(
                        renderer,
                        eventHandler,
                        videoListener,
                        audioListener,
                        textOutput,
                        metadataOutput
                );
            }
        };
    }

    public void initialize(ExoPlayer player) {
        if (!renderingSupported || initializedPlayer == player) return;
        if (initializedPlayer != null) {
            initializedPlayer.removeListener(handler);
            initializedPlayer.removeListener(frameSizeSyncListener);
        }
        initializedPlayer = player;
        handler.init(player);
        // Registered after AssHandler so its track update creates the render
        // before we correct the frame size to Silo's visible-video subtitle box.
        player.addListener(frameSizeSyncListener);
    }

    /** Adds one libass overlay beneath Media3's normal text cue layer. */
    public void attachTo(SubtitleView host) {
        if (!renderingSupported) return;
        AssSubtitleView overlay = null;
        for (int index = 0; index < host.getChildCount(); index++) {
            View child = host.getChildAt(index);
            if (child instanceof AssSubtitleView) {
                overlay = (AssSubtitleView) child;
                break;
            }
        }
        if (overlay == null) {
            overlay = new AssSubtitleView(host.getContext(), handler);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            // Index zero keeps Media3 text cues above libass if a malformed
            // stream exposes both representations at once.
            host.addView(overlay, 0, params);
            overlay.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> syncOverlayFrameSize());
        }
        overlayRef = new WeakReference<>(overlay);
        syncOverlayFrameSizeLater();
    }

    private Extractor[] replaceMatroska(
            Extractor[] extractors,
            SubtitleParser.Factory combinedParserFactory
    ) {
        for (int index = 0; index < extractors.length; index++) {
            Extractor extractor = extractors[index];
            if (extractor instanceof MatroskaExtractor
                    && !(extractor instanceof AssMatroskaExtractor)) {
                extractors[index] = new AssMatroskaExtractor(combinedParserFactory, handler);
            }
        }
        return extractors;
    }

    private void syncOverlayFrameSizeLater() {
        AssSubtitleView overlay = overlayRef.get();
        if (overlay != null) overlay.post(this::syncOverlayFrameSize);
    }

    private void syncOverlayFrameSize() {
        AssSubtitleView overlay = overlayRef.get();
        AssRender render = handler == null ? null : handler.getRender();
        if (overlay != null && render != null && overlay.getWidth() > 0 && overlay.getHeight() > 0) {
            render.setFrameSize(overlay.getWidth(), overlay.getHeight());
        }
    }

    private static boolean probeNativeRuntime() {
        boolean packagedAbi = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if (PACKAGED_ABIS.contains(abi)) {
                packagedAbi = true;
                break;
            }
        }
        if (!packagedAbi) return false;
        try {
            // Initializing Ass loads libasskt.so, proving the ABI can load
            // before Silo advertises authored ASS styling to the server.
            Class.forName(Ass.class.getName(), true, LibassBridge.class.getClassLoader());
            return true;
        } catch (LinkageError | ReflectiveOperationException error) {
            return false;
        }
    }

    private static boolean probeMatroskaIntegration() {
        try {
            // AssMatroskaExtractor verifies the private Media3 field names it
            // needs in its static initializer. Probe that compatibility before
            // claiming embedded-font support to the V3 server.
            Class.forName(
                    AssMatroskaExtractor.class.getName(),
                    true,
                    LibassBridge.class.getClassLoader()
            );
            return true;
        } catch (LinkageError | ReflectiveOperationException error) {
            return false;
        }
    }

    private static final class SiloLibassRenderer extends NoSampleRenderer {
        private final AssHandler handler;
        private final LongSupplier subtitleOffsetUs;

        private SiloLibassRenderer(AssHandler handler, LongSupplier subtitleOffsetUs) {
            this.handler = handler;
            this.subtitleOffsetUs = subtitleOffsetUs;
        }

        @Override
        public String getName() {
            return "SiloLibassRenderer";
        }

        @Override
        public void render(long positionUs, long elapsedRealtimeUs) {
            // Positive Silo offset means "show later", so render libass at an
            // earlier media time. Ordinary Media3 cues receive the same offset
            // by shifting their start timestamps in OffsetSubtitleParserFactory.
            handler.setVideoTime(
                    adjustedPositionUs(positionUs, subtitleOffsetUs.getAsLong())
            );
        }
    }

    static long adjustedPositionUs(long rendererPositionUs, long subtitleOffsetUs) {
        return rendererPositionUs
                - MEDIA3_RENDERER_POSITION_OFFSET_US
                - subtitleOffsetUs;
    }
}
