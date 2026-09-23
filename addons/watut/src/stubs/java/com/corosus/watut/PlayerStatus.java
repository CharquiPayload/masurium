package com.corosus.watut;

/**
 * A stand-in for WATUT's class, with only the names the mixins use, so the add-on
 * compiles without a copy of WATUT. It is never packaged: in the game, WATUT's own
 * class answers, and its constants are found by name.
 */
public class PlayerStatus {

    public enum PlayerGuiState {
        NONE,
        CHAT_SCREEN
    }
}
