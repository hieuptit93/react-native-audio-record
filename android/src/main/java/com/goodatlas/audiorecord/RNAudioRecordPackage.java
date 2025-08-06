package com.goodatlas.audiorecord;

import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.uimanager.ViewManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RNAudioRecordPackage implements ReactPackage {
    private RNAudioRecordModule audioRecordModule;

    @Override
    public List<NativeModule> createNativeModules(ReactApplicationContext reactContext) {
        List<NativeModule> modules = new ArrayList<>();
        audioRecordModule = new RNAudioRecordModule(reactContext);
        modules.add(audioRecordModule);
        return modules;
    }

    @Override
    public List<ViewManager> createViewManagers(ReactApplicationContext reactContext) {
        return Collections.emptyList();
    }

    public void onDestroy() {
        if (audioRecordModule != null) {
            audioRecordModule.cleanup();
            audioRecordModule = null;
        }
    }
}
