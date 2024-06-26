package com.monstrous.impostors.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider;
import com.badlogic.gdx.math.*;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ScreenUtils;
import com.monstrous.impostors.Settings;
import com.monstrous.impostors.gui.GUI;
import com.monstrous.impostors.inputs.CameraController;
import com.monstrous.impostors.inputs.KeyBinding;
import com.monstrous.impostors.scenery.Scenery;
import com.monstrous.impostors.scenery.SceneryDebug;
import com.monstrous.impostors.shaders.InstancedDecalShaderProvider;
import com.monstrous.impostors.shaders.InstancedDefaultShaderProvider;
import com.monstrous.impostors.shaders.InstancedPBRDepthShaderProvider;
import com.monstrous.impostors.shaders.InstancedPBRShaderProvider;
import com.monstrous.impostors.terrain.Terrain;
import com.monstrous.impostors.terrain.TerrainDebug;
import net.mgsx.gltf.loaders.gltf.GLTFLoader;
import net.mgsx.gltf.scene3d.attributes.FogAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRCubemapAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRFloatAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRTextureAttribute;
import net.mgsx.gltf.scene3d.lights.DirectionalShadowLight;
import net.mgsx.gltf.scene3d.scene.*;
import net.mgsx.gltf.scene3d.utils.IBLBuilder;


public class GameScreen extends ScreenAdapter {

    private static final int SHADOW_MAP_SIZE = 8192; //4096;

    private Main game;
    public SceneManager sceneManager;
    private SceneAsset sceneAsset;
    private Scene groundPlane;
    private PerspectiveCamera camera;
    private Cubemap diffuseCubemap;
    private Cubemap environmentCubemap;
    private Cubemap specularCubemap;
    private Texture brdfLUT;
    private SceneSkybox skybox;
    public DirectionalShadowLight light;
    private CameraController camController;
    private float cameraDistance;
    private GUI gui;
    private ModelBatch modelBatch;
    public CascadeShadowMap csm;
    private Terrain terrain;
    private TerrainDebug terrainDebug;
    private SceneryDebug sceneryDebug;
    public Scenery scenery;
    private int width, height;
    private boolean guiMode = false;

    public GameScreen(Main game) {
        this.game = game;
    }

    @Override
    public void show() {

        if (Gdx.gl30 == null) {
            throw new GdxRuntimeException("GLES 3.0 profile required for this programme.");
        }




        // hide the mouse cursor and fix it to screen centre, so it doesn't go out the window canvas
        Gdx.input.setCursorCatched(true);
        Gdx.input.setCursorPosition(Gdx.graphics.getWidth() / 2, Gdx.graphics.getHeight() / 2);

        // create scene manager
        // but use our own shader providers for PBR or default shaders that support instanced meshes
        //
        if(Settings.usePBRshader)
            sceneManager = new SceneManager( new InstancedPBRShaderProvider(), new InstancedPBRDepthShaderProvider() );
        else
            sceneManager = new SceneManager( new InstancedDefaultShaderProvider(), new InstancedPBRDepthShaderProvider() );

        // setup camera
        camera = new PerspectiveCamera(Settings.cameraFOV, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cameraDistance = 40f;
        camera.near = 1f;
        camera.far = Settings.cameraFar;
        camera.position.set(0, 20, 50);
		camera.up.set(Vector3.Y);
		camera.lookAt(Vector3.Zero);
		camera.update();
        sceneManager.setCamera(camera);



        terrain = new Terrain(camera.position);
        terrainDebug = new TerrainDebug(terrain);

        scenery = new Scenery(terrain, Settings.scenerySeparationDistance);
        sceneryDebug = new SceneryDebug( scenery );


        gui = new GUI( this );

        camera.position.set(0, terrain.getHeight(0, 50) + 10, 50);

        // input multiplexer to input to GUI and to cam controller
        InputMultiplexer im = new InputMultiplexer();
        Gdx.input.setInputProcessor(im);
        camController = new CameraController(camera, terrain);
        im.addProcessor(gui.stage);
        im.addProcessor(camController);


        setLighting();

        // setup skybox
        skybox = new SceneSkybox(environmentCubemap);
        // don't use the skybox to get better fog blending with sky colour
        //sceneManager.setSkyBox(skybox);

        updateFogSettings();

        sceneAsset = new GLTFLoader().load(Gdx.files.internal("models/duck-land.gltf"));
        groundPlane = new Scene(sceneAsset.scene, "groundPlane");

        modelBatch = new ModelBatch( new InstancedDecalShaderProvider() );      // to render the impostors
    }

    public void setLighting(){
        sceneManager.environment.set(new PBRFloatAttribute(PBRFloatAttribute.ShadowBias, 1f/Settings.inverseShadowBias));

        if(Settings.cascadedShadows) {
            csm = new CascadeShadowMap(Settings.numCascades);
            sceneManager.setCascadeShadowMap(csm);
        }

        // setup light
        // set the light parameters so that your area of interest is in the shadow light frustum
        // but keep it reasonably tight to keep sharper shadows

        float farPlane = 300;
        float nearPlane = 0;
        float VP_SIZE = Settings.shadowViewportSize;
        light = new DirectionalShadowLight(SHADOW_MAP_SIZE, SHADOW_MAP_SIZE).setViewport(VP_SIZE,VP_SIZE,nearPlane,farPlane);

        light.direction.set(1, -3, 1).nor();
        light.color.set(Color.WHITE);
        light.intensity = Settings.directionalLightLevel;
        sceneManager.environment.add(light);

        // setup quick IBL (image based lighting)
        IBLBuilder iblBuilder = IBLBuilder.createOutdoor(light);
        environmentCubemap = iblBuilder.buildEnvMap(1024);
        diffuseCubemap = iblBuilder.buildIrradianceMap(256);
        specularCubemap = iblBuilder.buildRadianceMap(10);
        iblBuilder.dispose();

        // This texture is provided by the library, no need to have it in your assets.
        brdfLUT = new Texture(Gdx.files.classpath("net/mgsx/gltf/shaders/brdfLUT.png"));

        sceneManager.setAmbientLight(Settings.ambientLightLevel);
        sceneManager.environment.set(new PBRTextureAttribute(PBRTextureAttribute.BRDFLUTTexture, brdfLUT));
        sceneManager.environment.set(PBRCubemapAttribute.createSpecularEnv(specularCubemap));
        sceneManager.environment.set(PBRCubemapAttribute.createDiffuseEnv(diffuseCubemap));

    }

    public void updateFogSettings() {
        sceneManager.environment.set(new ColorAttribute(ColorAttribute.Fog, Settings.fogColor));
        sceneManager.environment.set(new FogAttribute(FogAttribute.FogEquation).set(Settings.fogNear, Settings.fogFar, Settings.fogExponent));
    }


    @Override
    public void render(float deltaTime) {

        if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)){
            game.setScreen(new MenuScreen(game));
            return;
        }

        if(Gdx.input.isKeyJustPressed(KeyBinding.CYCLE_LOD.getKeyCode())){
            if(Settings.lodLevel == Settings.LOD_LEVELS)
                Settings.lodLevel = -1; // mixed mode
            else
                Settings.lodLevel++;
        }
        if(Gdx.input.isKeyJustPressed(KeyBinding.TERRAIN_OVERLAY.getKeyCode()))
            Settings.debugTerrainChunkAllocation = !Settings.debugTerrainChunkAllocation;
        if(Gdx.input.isKeyJustPressed(KeyBinding.FOG_MENU.getKeyCode())) {
            Settings.showFogSettings = !Settings.showFogSettings;
            gui.showFogMenu(Settings.showFogSettings);
            Gdx.input.setCursorCatched(!Settings.showFogSettings);
            guiMode = Settings.showFogSettings;
        }
        if(Gdx.input.isKeyJustPressed(KeyBinding.LIGHT_MENU.getKeyCode())) {
            Settings.showLightSettings = !Settings.showLightSettings;
            gui.showLightMenu(Settings.showLightSettings);
            Gdx.input.setCursorCatched(!Settings.showLightSettings);
            guiMode = Settings.showLightSettings;
        }
        if(Gdx.input.isKeyJustPressed(KeyBinding.SCENERY_OVERLAY.getKeyCode()))
            Settings.debugSceneryChunkAllocation = !Settings.debugSceneryChunkAllocation;
        if(Gdx.input.isKeyJustPressed(KeyBinding.SINGLE_INSTANCE.getKeyCode())) {
            Settings.singleInstance = !Settings.singleInstance;
            if(Settings.singleInstance) {
                camera.position.set(0, 20, 50);
                camera.lookAt(Vector3.Zero);
            }
            else
                Settings.lodLevel = -1;
        }

        if(Gdx.input.isKeyJustPressed(KeyBinding.INCREASE_LOD_DISTANCE.getKeyCode())) {
            for(int lod = 0; lod < Settings.LOD_LEVELS; lod++)
                Settings.lodDistances[lod] = 1.1f * Settings.lodDistances[lod];
            Settings.dynamicLODAdjustment = false;
            Gdx.app.log("Update LOD1 distance to:", ""+Settings.lodDistances[0]);
            scenery.update( deltaTime, camera, true );
        }
        if(Gdx.input.isKeyJustPressed(KeyBinding.DECREASE_LOD_DISTANCE.getKeyCode())) {
            for(int lod = 0; lod < Settings.LOD_LEVELS; lod++)
                Settings.lodDistances[lod] = 0.9f * Settings.lodDistances[lod];
            Gdx.app.log("Update LOD1 distance to:", ""+Settings.lodDistances[0]);
            Settings.dynamicLODAdjustment = false;
            scenery.update( deltaTime, camera, true );
        }
        // Use key to toggle full screen / windowed screen
        if (Gdx.input.isKeyJustPressed(KeyBinding.TOGGLE_FULLSCREEN.getKeyCode())) {
            if (!Gdx.graphics.isFullscreen()) {
                width = Gdx.graphics.getWidth();
                height = Gdx.graphics.getHeight();
                Gdx.graphics.setFullscreenMode(Gdx.graphics.getDisplayMode());
                Gdx.app.log("To fullscreen", "from "+width+" x "+height);
            } else {
                Gdx.graphics.setWindowedMode(width, height);
                Gdx.app.log("To windowed mode", "" + width + " x " + height);
            }
        }

        camera.up.set(Vector3.Y);
        if(!guiMode)
            camController.update( deltaTime );

        terrain.update( camera );
        scenery.update( deltaTime, camera, !Settings.skipChecksWhenCameraStill );

        if(Settings.cascadedShadows) {
            csm.setCascades(sceneManager.camera, light, 0, Settings.cascadeSplitDivisor);
        }
        else
            light.setCenter(camera.position); // keep shadow light on player so that we have shadows

        sceneManager.getRenderableProviders().clear();

        // terrain chunks are taken directly from the Terrain class
        if(Settings.singleInstance) {
            // if we are viewing a single instance, just add a little green tile to stand on
            sceneManager.addScene(groundPlane, false);
        } else {
            // add visible terrain chunks
            for (Scene scene : terrain.getScenes())
                sceneManager.addScene(scene, false);
        }

        // add scenery (instanced objects)
        for(Scene scene : scenery.getScenes())
            sceneManager.addScene(scene, false);

        // render
        ScreenUtils.clear(Color.SKY, true);

        sceneManager.update(deltaTime);
        sceneManager.render();

        if(Settings.lodLevel == Settings.LOD_LEVELS || Settings.lodLevel < 0 ) {      // impostors
            modelBatch.begin(camera);
            modelBatch.render(scenery.getImpostors());
            modelBatch.end();
        }

        terrainDebug.debugRender(Vector3.Zero, camera.position);
        sceneryDebug.debugRender(Vector3.Zero, camera.position);

        gui.render(deltaTime);

        if(Settings.dynamicLODAdjustment)
            adjustDetailToFrameRate(deltaTime, 60);
    }


    private int numSamples = 0;
    private float totalTime = 0;
    private float sampleTime = 1;

    // dynamic adjustment of quality settings to achieve an acceptable minimum frame rate.
    // This checks often on start up, once the minimum frame rate is achieved it will check less often just in case the frame rate gets worse.
    // Note: in case of a very high frame rate, we don't reduce the LOD levels to make it slower/better quality.
    //
    private void adjustDetailToFrameRate(float deltaTime, float targetFrameRate ){

        totalTime += deltaTime;
        numSamples++;
        if(totalTime > sampleTime){        // every few seconds check frame rate
            float frameRate =  numSamples / totalTime;
            Gdx.app.log("fps (avg)", ""+frameRate);

            if(frameRate < targetFrameRate ){
                // to improve performance, make LOD distances smaller
                for(int lod = 0; lod < Settings.LOD_LEVELS; lod++)
                    Settings.lodDistances[lod] = 0.7f * Settings.lodDistances[lod];

                Gdx.app.log("Frame rate too low, increasing LOD1 distance to:", ""+Settings.lodDistances[0]);
            }
            else {
                if(sampleTime < 10)
                    Gdx.app.log("Target frame rate achieved", "(min "+targetFrameRate+")");
                sampleTime = 10f;   // recheck every so often, but with lower frequency
            }
            numSamples = 0;
            totalTime = 0;
        }

    }




    @Override
    public void resize(int width, int height) {
        sceneManager.updateViewport(width, height);
        gui.resize(width, height);
    }


    @Override
    public void hide () {

        Gdx.input.setCursorCatched(false);
        dispose();
    }

    @Override
    public void dispose() {
        sceneManager.dispose();
        environmentCubemap.dispose();
        diffuseCubemap.dispose();
        specularCubemap.dispose();
        brdfLUT.dispose();
        skybox.dispose();
        gui.dispose();
        terrain.dispose();
        terrainDebug.dispose();
        scenery.dispose();
        sceneAsset.dispose();
        if(Settings.cascadedShadows)
            csm.dispose();
    }

}
