package com.hooya.stabilizedturret.client.preview;

import java.lang.reflect.Method;

/** Optional qModeling CPU-preview scope. No class dependency when qModeling is absent. */
final class PreviewExternalModels implements AutoCloseable {
    private static Method enter,exit;
    private static boolean checked;
    private final boolean entered;
    PreviewExternalModels() {
        if(!checked) {
            checked=true;
            try {
                Class<?> scope=Class.forName("qwxon.qmodeling.client.render.WorkspaceBlueprintPreviewScope");
                enter=scope.getMethod("enter");exit=scope.getMethod("exit");
            } catch(ReflectiveOperationException ignored) {}
        }
        boolean active=false;
        if(enter!=null)try {enter.invoke(null);active=true;}catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot enter qModeling preview scope",e);}
        entered=active;
    }
    @Override public void close() {
        if(entered)try{exit.invoke(null);}catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot exit qModeling preview scope",e);}
    }
}
