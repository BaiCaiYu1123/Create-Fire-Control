// SPDX-License-Identifier: MIT
// Copyright (c) 2026 BaiCaiYu
package com.caca.copypoly.client;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Shared preference bridge; the host owns persistence without adding host dependencies to MIT code. */
public final class EditorPreferences {
    private static BooleanSupplier read=()->true;
    private static Consumer<Boolean> write=value->{};
    private static BooleanSupplier readGroupTextures=()->true;
    private static Consumer<Boolean> writeGroupTextures=value->{};
    public static void bind(BooleanSupplier getter,Consumer<Boolean> setter){read=getter;write=setter;}
    static boolean xray(){return read.getAsBoolean();}
    static void xray(boolean value){write.accept(value);}
    public static void bindGroupTextures(BooleanSupplier getter,Consumer<Boolean> setter) {
        readGroupTextures=getter;
        writeGroupTextures=setter;
    }
    static boolean groupTextures(){return readGroupTextures.getAsBoolean();}
    static void groupTextures(boolean value){writeGroupTextures.accept(value);}
    private EditorPreferences(){}
}
