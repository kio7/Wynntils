/*
 * Copyright © Wynntils 2022.
 * This file is released under AGPLv3. See LICENSE for full license details.
 */
package com.wynntils.features.map;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.wynntils.core.components.Models;
import com.wynntils.core.config.Category;
import com.wynntils.core.config.Config;
import com.wynntils.core.config.ConfigCategory;
import com.wynntils.core.config.ConfigHolder;
import com.wynntils.core.features.UserFeature;
import com.wynntils.core.features.overlays.Overlay;
import com.wynntils.core.features.overlays.OverlayPosition;
import com.wynntils.core.features.overlays.annotations.OverlayInfo;
import com.wynntils.core.features.overlays.sizes.GuiScaledOverlaySize;
import com.wynntils.mc.event.RenderEvent;
import com.wynntils.models.map.MapTexture;
import com.wynntils.models.map.pois.PlayerMiniMapPoi;
import com.wynntils.models.map.pois.Poi;
import com.wynntils.models.map.pois.WaypointPoi;
import com.wynntils.utils.MathUtils;
import com.wynntils.utils.StringUtils;
import com.wynntils.utils.colors.CommonColors;
import com.wynntils.utils.colors.CustomColor;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.render.FontRenderer;
import com.wynntils.utils.render.MapRenderer;
import com.wynntils.utils.render.RenderUtils;
import com.wynntils.utils.render.TextRenderSetting;
import com.wynntils.utils.render.TextRenderTask;
import com.wynntils.utils.render.Texture;
import com.wynntils.utils.render.type.HorizontalAlignment;
import com.wynntils.utils.render.type.PointerType;
import com.wynntils.utils.render.type.TextShadow;
import com.wynntils.utils.render.type.VerticalAlignment;
import com.wynntils.utils.type.BoundingBox;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;

@ConfigCategory(Category.MAP)
public class MinimapFeature extends UserFeature {
    public static MinimapFeature INSTANCE;

    @OverlayInfo(renderType = RenderEvent.ElementType.GUI, renderAt = OverlayInfo.RenderState.Pre)
    public final MinimapOverlay minimapOverlay = new MinimapOverlay();

    public static class MinimapOverlay extends Overlay {
        private static final int DEFAULT_SIZE = 150;

        @Config
        public float scale = 1f;

        @Config
        public float poiScale = 0.8f;

        @Config
        public float pointerScale = 1f;

        @Config
        public boolean followPlayerRotation = true;

        @Config
        public boolean renderUsingLinear = true;

        @Config
        public CustomColor pointerColor = new CustomColor(1f, 1f, 1f, 1f);

        @Config
        public MapMaskType maskType = MapMaskType.Circle;

        @Config
        public MapBorderType borderType = MapBorderType.Wynn;

        @Config
        public PointerType pointerType = PointerType.Arrow;

        @Config
        public CompassRenderType showCompass = CompassRenderType.All;

        @Config(subcategory = "Remote Players")
        public boolean renderRemoteFriendPlayers = true;

        @Config(subcategory = "Remote Players")
        public boolean renderRemotePartyPlayers = true;

        @Config(subcategory = "Remote Players")
        public float remotePlayersHeadScale = 0.6f;

        protected MinimapOverlay() {
            super(
                    new OverlayPosition(
                            5,
                            5,
                            VerticalAlignment.Top,
                            HorizontalAlignment.Left,
                            OverlayPosition.AnchorSection.TopLeft),
                    new GuiScaledOverlaySize(DEFAULT_SIZE, DEFAULT_SIZE));
        }

        // FIXME: This is the only overlay not to use buffer sources for rendering. This is due to `createMask`
        // currently not working with buffer sources.
        @Override
        public void render(
                PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, float partialTicks, Window window) {
            if (!Models.WorldState.onWorld()) return;

            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

            float width = getWidth();
            float height = getHeight();
            float renderX = getRenderX();
            float renderY = getRenderY();

            float centerX = renderX + width / 2;
            float centerZ = renderY + height / 2;

            double playerX = McUtils.player().getX();
            double playerZ = McUtils.player().getZ();

            BoundingBox textureBoundingBox =
                    BoundingBox.centered((float) playerX, (float) playerZ, width * scale, height * scale);

            // enable mask
            switch (maskType) {
                case Rectangular -> RenderUtils.enableScissor((int) renderX, (int) renderY, (int) width, (int) height);
                case Circle -> RenderUtils.createMask(
                        poseStack, Texture.CIRCLE_MASK, (int) renderX, (int) renderY, (int) (renderX + width), (int)
                                (renderY + height));
            }

            // Always draw a black background to cover transparent map areas
            RenderUtils.drawRect(poseStack, CommonColors.BLACK, renderX, renderY, 0, width, height);

            // enable rotation if necessary
            if (followPlayerRotation) {
                poseStack.pushPose();
                RenderUtils.rotatePose(
                        poseStack, centerX, centerZ, 180 - McUtils.player().getYRot());
            }

            // avoid rotational overpass - This is a rather loose oversizing, if possible later
            // use trignometry, etc. to find a better one
            float extraFactor = 1f;
            if (followPlayerRotation) {
                // 1.5 > sqrt(2);
                extraFactor = 1.5F;

                if (width > height) {
                    extraFactor *= width / height;
                } else {
                    extraFactor *= height / width;
                }
            }

            List<MapTexture> maps = Models.Map.getMapsForBoundingBox(textureBoundingBox);
            for (MapTexture map : maps) {
                float textureX = map.getTextureXPosition(playerX);
                float textureZ = map.getTextureZPosition(playerZ);
                MapRenderer.renderMapQuad(
                        map,
                        poseStack,
                        centerX,
                        centerZ,
                        textureX,
                        textureZ,
                        width * extraFactor,
                        height * extraFactor,
                        this.scale,
                        this.renderUsingLinear);
            }

            // disable rotation if necessary
            if (followPlayerRotation) {
                poseStack.popPose();
            }

            renderPois(poseStack, centerX, centerZ, width, height, playerX, playerZ, textureBoundingBox);

            // cursor
            MapRenderer.renderCursor(
                    poseStack,
                    centerX,
                    centerZ,
                    this.pointerScale,
                    this.pointerColor,
                    this.pointerType,
                    followPlayerRotation);

            // disable mask & render border
            switch (maskType) {
                case Rectangular -> RenderSystem.disableScissor();
                case Circle -> RenderUtils.clearMask();
            }

            // render border
            renderMapBorder(poseStack, renderX, renderY, width, height);

            // Directional Text
            renderCardinalDirections(poseStack, width, height, centerX, centerZ);
        }

        private void renderPois(
                PoseStack poseStack,
                float centerX,
                float centerZ,
                float width,
                float height,
                double playerX,
                double playerZ,
                BoundingBox textureBoundingBox) {
            float sinRotationRadians;
            float cosRotationRadians;

            if (followPlayerRotation) {
                double rotationRadians = Math.toRadians(McUtils.player().getYRot());
                sinRotationRadians = (float) StrictMath.sin(rotationRadians);
                cosRotationRadians = (float) -StrictMath.cos(rotationRadians);
            } else {
                sinRotationRadians = 0f;
                cosRotationRadians = 0f;
            }

            float currentZoom = 1f / scale;

            Stream<? extends Poi> poisToRender = Models.Map.getServicePois().stream();

            poisToRender = Stream.concat(
                    poisToRender,
                    Models.Hades.getHadesUsers()
                            .filter(user -> (user.isPartyMember() && renderRemotePartyPlayers)
                                    || (user.isMutualFriend() && renderRemoteFriendPlayers))
                            .map(PlayerMiniMapPoi::new));

            poisToRender = Stream.concat(poisToRender, Models.Map.getCombatPois().stream());
            poisToRender = Stream.concat(poisToRender, MapFeature.INSTANCE.customPois.stream());

            MultiBufferSource.BufferSource bufferSource =
                    McUtils.mc().renderBuffers().bufferSource();

            Poi[] pois = poisToRender.toArray(Poi[]::new);
            for (Poi poi : pois) {
                float dX = (poi.getLocation().getX() - (float) playerX) / scale;
                float dZ = (poi.getLocation().getZ() - (float) playerZ) / scale;

                if (followPlayerRotation) {
                    float tempdX = dX * cosRotationRadians - dZ * sinRotationRadians;

                    dZ = dX * sinRotationRadians + dZ * cosRotationRadians;
                    dX = tempdX;
                }

                float poiRenderX = centerX + dX;
                float poiRenderZ = centerZ + dZ;

                float poiWidth = poi.getWidth(currentZoom, poiScale);
                float poiHeight = poi.getHeight(currentZoom, poiScale);

                BoundingBox box = BoundingBox.centered(
                        poi.getLocation().getX(), poi.getLocation().getZ(), (int) poiWidth, (int) poiHeight);

                if (box.intersects(textureBoundingBox)) {
                    poi.renderAt(poseStack, bufferSource, poiRenderX, poiRenderZ, false, poiScale, currentZoom);
                }
            }

            bufferSource.endBatch();

            // Compass icon
            Optional<WaypointPoi> compassOpt = Models.Compass.getCompassWaypoint();

            if (compassOpt.isEmpty()) return;

            WaypointPoi compass = compassOpt.get();

            float compassOffsetX = (compass.getLocation().getX() - (float) playerX) / scale;
            float compassOffsetZ = (compass.getLocation().getZ() - (float) playerZ) / scale;

            if (followPlayerRotation) {
                float tempCompassOffsetX = compassOffsetX * cosRotationRadians - compassOffsetZ * sinRotationRadians;

                compassOffsetZ = compassOffsetX * sinRotationRadians + compassOffsetZ * cosRotationRadians;
                compassOffsetX = tempCompassOffsetX;
            }

            final float compassSize =
                    Math.max(compass.getWidth(currentZoom, poiScale), compass.getHeight(currentZoom, poiScale)) * 0.8f;

            float compassRenderX = compassOffsetX + centerX;
            float compassRenderZ = compassOffsetZ + centerZ;

            // Normalize offset for later
            float distance = MathUtils.magnitude(compassOffsetX, compassOffsetZ);
            compassOffsetX /= distance;
            compassOffsetZ /= distance;

            // Subtract compassSize so scaled remains within boundary
            float scaledWidth = width - 2 * compassSize;
            float scaledHeight = height - 2 * compassSize;

            float toBorderScale = 1f;

            if (maskType == MapMaskType.Rectangular) {
                // Scale as necessary
                toBorderScale =
                        Math.min(scaledWidth / Math.abs(compassOffsetX), scaledHeight / Math.abs(compassOffsetZ)) / 2;
            } else if (maskType == MapMaskType.Circle) {
                toBorderScale = scaledWidth
                        / (MathUtils.magnitude(compassOffsetX, compassOffsetZ * scaledWidth / scaledHeight))
                        / 2;
            }

            if (toBorderScale < distance) {
                // Scale to border
                compassRenderX = centerX + compassOffsetX * toBorderScale;
                compassRenderZ = centerZ + compassOffsetZ * toBorderScale;

                // Replace with pointer
                float angle = (float) Math.toDegrees(StrictMath.atan2(compassOffsetZ, compassOffsetX)) + 90f;

                poseStack.pushPose();
                RenderUtils.rotatePose(poseStack, compassRenderX, compassRenderZ, angle);
                compass.getPointerPoi()
                        .renderAt(poseStack, bufferSource, compassRenderX, compassRenderZ, false, poiScale, 1f / scale);
                poseStack.popPose();
            } else {
                compass.renderAt(poseStack, bufferSource, compassRenderX, compassRenderZ, false, poiScale, currentZoom);
            }

            bufferSource.endBatch();

            poseStack.pushPose();
            poseStack.translate(centerX, centerZ, 0);
            poseStack.scale(0.8f, 0.8f, 1);
            poseStack.translate(-centerX, -centerZ, 0);

            FontRenderer fontRenderer = FontRenderer.getInstance();
            Font font = fontRenderer.getFont();

            String text = StringUtils.integerToShortString(Math.round(distance * scale)) + "m";
            float w = font.width(text) / 2f, h = font.lineHeight / 2f;

            RenderUtils.drawRect(
                    poseStack,
                    new CustomColor(0f, 0f, 0f, 0.7f),
                    compassRenderX - w - 3f,
                    compassRenderZ - h - 1f,
                    0,
                    2 * w + 6,
                    2 * h + 1);
            fontRenderer.renderText(
                    poseStack,
                    text,
                    compassRenderX,
                    compassRenderZ - 3f,
                    CommonColors.WHITE,
                    HorizontalAlignment.Center,
                    VerticalAlignment.Top,
                    TextShadow.NORMAL);

            poseStack.popPose();
        }

        private void renderCardinalDirections(
                PoseStack poseStack, float width, float height, float centerX, float centerZ) {
            if (showCompass == CompassRenderType.None) return;

            float northDX;
            float northDY;

            if (followPlayerRotation) {
                float yawRadians = (float) Math.toRadians(McUtils.player().getYRot());
                northDX = (float) StrictMath.sin(yawRadians);
                northDY = (float) StrictMath.cos(yawRadians);

                double toBorderScaleNorth = 1;

                if (maskType == MapMaskType.Rectangular) {
                    toBorderScaleNorth = Math.min(width / Math.abs(northDX), height / Math.abs(northDY)) / 2;
                } else if (maskType == MapMaskType.Circle) {
                    toBorderScaleNorth = width / (MathUtils.magnitude(northDX, northDY * width / height)) / 2;
                }

                northDX *= toBorderScaleNorth;
                northDY *= toBorderScaleNorth;

            } else {
                northDX = 0;
                northDY = -height / 2;
            }

            FontRenderer.getInstance()
                    .renderText(
                            poseStack,
                            centerX + northDX,
                            centerZ + northDY,
                            new TextRenderTask("N", TextRenderSetting.CENTERED));

            if (showCompass == CompassRenderType.North) return;

            // we can't do manipulations from north to east as it might not be square
            float eastDX;
            float eastDY;

            if (followPlayerRotation) {
                eastDX = -northDY;
                eastDY = northDX;

                double toBorderScaleEast = 1f;

                if (maskType == MapMaskType.Rectangular) {
                    toBorderScaleEast = Math.min(width / Math.abs(northDY), height / Math.abs(northDX)) / 2;
                } else if (maskType == MapMaskType.Circle) {
                    toBorderScaleEast = width / (MathUtils.magnitude(eastDX, eastDY * width / height)) / 2;
                }

                eastDX *= toBorderScaleEast;
                eastDY *= toBorderScaleEast;
            } else {
                eastDX = width / 2;
                eastDY = 0;
            }

            FontRenderer.getInstance()
                    .renderText(
                            poseStack,
                            centerX + eastDX,
                            centerZ + eastDY,
                            new TextRenderTask("E", TextRenderSetting.CENTERED));
            FontRenderer.getInstance()
                    .renderText(
                            poseStack,
                            centerX - northDX,
                            centerZ - northDY,
                            new TextRenderTask("S", TextRenderSetting.CENTERED));
            FontRenderer.getInstance()
                    .renderText(
                            poseStack,
                            centerX - eastDX,
                            centerZ - eastDY,
                            new TextRenderTask("W", TextRenderSetting.CENTERED));
        }

        private void renderMapBorder(PoseStack poseStack, float renderX, float renderY, float width, float height) {
            Texture texture = borderType.texture();
            int grooves = borderType.groovesSize();
            BorderInfo borderInfo = maskType == MapMaskType.Circle ? borderType.circle() : borderType.square();
            int tx1 = borderInfo.tx1();
            int ty1 = borderInfo.ty1();
            int tx2 = borderInfo.tx2();
            int ty2 = borderInfo.ty2();

            // Scale to stay the same.
            float groovesWidth = grooves * width / DEFAULT_SIZE;
            float groovesHeight = grooves * height / DEFAULT_SIZE;

            RenderUtils.drawTexturedRect(
                    poseStack,
                    texture.resource(),
                    renderX - groovesWidth,
                    renderY - groovesHeight,
                    0,
                    width + 2 * groovesWidth,
                    height + 2 * groovesHeight,
                    tx1,
                    ty1,
                    tx2 - tx1,
                    ty2 - ty1,
                    texture.width(),
                    texture.height());
        }

        @Override
        protected void onConfigUpdate(ConfigHolder configHolder) {}
    }

    public enum CompassRenderType {
        None,
        North,
        All
    }

    public enum MapMaskType {
        Rectangular,
        Circle
    }

    public enum MapBorderType {
        Gilded(Texture.GILDED_MAP_TEXTURES, new BorderInfo(0, 262, 262, 524), new BorderInfo(0, 0, 262, 262), 1),
        Paper(Texture.PAPER_MAP_TEXTURES, new BorderInfo(0, 0, 217, 217), new BorderInfo(0, 217, 217, 438), 3),
        Wynn(Texture.WYNN_MAP_TEXTURES, new BorderInfo(0, 0, 112, 112), new BorderInfo(0, 112, 123, 235), 3);
        private final Texture texture;
        private final BorderInfo square;
        private final BorderInfo circle;
        private final int groovesSize;

        MapBorderType(Texture texture, BorderInfo square, BorderInfo circle, int groovesSize) {
            this.texture = texture;
            this.square = square;
            this.circle = circle;
            this.groovesSize = groovesSize;
        }

        public Texture texture() {
            return texture;
        }

        public int groovesSize() {
            return groovesSize;
        }

        public BorderInfo square() {
            return square;
        }

        public BorderInfo circle() {
            return circle;
        }
    }

    public record BorderInfo(int tx1, int ty1, int tx2, int ty2) {}
}