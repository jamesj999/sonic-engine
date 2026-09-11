package com.openggf.configuration;

import static org.lwjgl.glfw.GLFW.*;

final class LegacyAwtKeyCodeMapper {
    private LegacyAwtKeyCodeMapper() {
    }

    static int toGlfw(int legacyCode) {
        return switch (legacyCode) {
            case 65 -> GLFW_KEY_A;
            case 66 -> GLFW_KEY_B;
            case 67 -> GLFW_KEY_C;
            case 68 -> GLFW_KEY_D;
            case 69 -> GLFW_KEY_E;
            case 70 -> GLFW_KEY_F;
            case 71 -> GLFW_KEY_G;
            case 72 -> GLFW_KEY_H;
            case 73 -> GLFW_KEY_I;
            case 74 -> GLFW_KEY_J;
            case 75 -> GLFW_KEY_K;
            case 76 -> GLFW_KEY_L;
            case 77 -> GLFW_KEY_M;
            case 78 -> GLFW_KEY_N;
            case 79 -> GLFW_KEY_O;
            case 80 -> GLFW_KEY_P;
            case 81 -> GLFW_KEY_Q;
            case 82 -> GLFW_KEY_R;
            case 83 -> GLFW_KEY_S;
            case 84 -> GLFW_KEY_T;
            case 85 -> GLFW_KEY_U;
            case 86 -> GLFW_KEY_V;
            case 87 -> GLFW_KEY_W;
            case 88 -> GLFW_KEY_X;
            case 89 -> GLFW_KEY_Y;
            case 90 -> GLFW_KEY_Z;
            case 48 -> GLFW_KEY_0;
            case 49 -> GLFW_KEY_1;
            case 50 -> GLFW_KEY_2;
            case 51 -> GLFW_KEY_3;
            case 52 -> GLFW_KEY_4;
            case 53 -> GLFW_KEY_5;
            case 54 -> GLFW_KEY_6;
            case 55 -> GLFW_KEY_7;
            case 56 -> GLFW_KEY_8;
            case 57 -> GLFW_KEY_9;
            case 32 -> GLFW_KEY_SPACE;
            case 10 -> GLFW_KEY_ENTER;
            case 27 -> GLFW_KEY_ESCAPE;
            case 9 -> GLFW_KEY_TAB;
            case 8 -> GLFW_KEY_BACKSPACE;
            case 155 -> GLFW_KEY_INSERT;
            case 127 -> GLFW_KEY_DELETE;
            case 39 -> GLFW_KEY_RIGHT;
            case 37 -> GLFW_KEY_LEFT;
            case 40 -> GLFW_KEY_DOWN;
            case 38 -> GLFW_KEY_UP;
            case 33 -> GLFW_KEY_PAGE_UP;
            case 34 -> GLFW_KEY_PAGE_DOWN;
            case 36 -> GLFW_KEY_HOME;
            case 35 -> GLFW_KEY_END;
            case 112 -> GLFW_KEY_F1;
            case 113 -> GLFW_KEY_F2;
            case 114 -> GLFW_KEY_F3;
            case 115 -> GLFW_KEY_F4;
            case 116 -> GLFW_KEY_F5;
            case 117 -> GLFW_KEY_F6;
            case 118 -> GLFW_KEY_F7;
            case 119 -> GLFW_KEY_F8;
            case 120 -> GLFW_KEY_F9;
            case 121 -> GLFW_KEY_F10;
            case 122 -> GLFW_KEY_F11;
            case 123 -> GLFW_KEY_F12;
            case 16 -> GLFW_KEY_LEFT_SHIFT;
            case 17 -> GLFW_KEY_LEFT_CONTROL;
            case 18 -> GLFW_KEY_LEFT_ALT;
            default -> legacyCode;
        };
    }
}
