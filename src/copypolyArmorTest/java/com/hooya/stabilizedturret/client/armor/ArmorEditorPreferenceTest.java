package com.hooya.stabilizedturret.client.armor;

import com.hooya.stabilizedturret.client.ClientVisualConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.*;
import net.neoforged.fml.loading.*;
import net.neoforged.neoforgespi.language.IModInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ArmorEditorPreferenceTest {
    @Test void editorPreferencesSurviveAnActualClientConfigUnloadAndReload(@TempDir Path directory) throws Exception {
        FMLPaths.loadAbsolutePaths(directory);FMLConfig.load();FMLConfig.updateConfig(FMLConfig.ConfigValue.DISABLE_CONFIG_WATCHER,true);
        var tracker=new ConfigTracker();var config=directory.resolve("config");Files.createDirectories(config);
        tracker.registerConfig(ModConfig.Type.CLIENT,ClientVisualConfig.SPEC,new TestContainer(),ClientVisualConfig.FILE);
        tracker.loadConfigs(ModConfig.Type.CLIENT,config);
        try{
            assertTrue(ClientVisualConfig.armorEditorXray());ClientVisualConfig.armorEditorXray(false);
            assertTrue(ClientVisualConfig.armorGroupTextures());ClientVisualConfig.armorGroupTextures(false);
            String saved=Files.readString(config.resolve(ClientVisualConfig.FILE));
            assertTrue(saved.contains("xray = false"));
            assertTrue(saved.contains("groupTextures = false"));
            tracker.unloadConfigs(ModConfig.Type.CLIENT);tracker.loadConfigs(ModConfig.Type.CLIENT,config);assertFalse(ClientVisualConfig.armorEditorXray());
            assertFalse(ClientVisualConfig.armorGroupTextures());
            ClientVisualConfig.armorGroupTextures(true);
            assertFalse(ClientVisualConfig.armorEditorXray());
            ClientVisualConfig.armorEditorXray(true);tracker.unloadConfigs(ModConfig.Type.CLIENT);tracker.loadConfigs(ModConfig.Type.CLIENT,config);assertTrue(ClientVisualConfig.armorEditorXray());
            assertTrue(ClientVisualConfig.armorGroupTextures());
            assertTrue(ClientVisualConfig.showAircraftHud());assertTrue(ClientVisualConfig.showBombIndicator());
        }finally{tracker.unloadConfigs(ModConfig.Type.CLIENT);}
    }
    private static final class TestContainer extends ModContainer {
        TestContainer(){super((IModInfo)Proxy.newProxyInstance(IModInfo.class.getClassLoader(),new Class<?>[]{IModInfo.class},(proxy,method,args)->method.getName().equals("getModId")?"armor_editor_preference_test":null));}
        @Override public IEventBus getEventBus(){return null;}
    }
}
