package org.lwjgl.sdl;

public final class SDLKeycode {
	private SDLKeycode() {}

	public static final int SDLK_SCANCODE_MASK = 1 << 30;
	public static final int SDLK_EXTENDED_MASK = 1 << 29;

	public static final int SDLK_RETURN = 0x0D;
	public static final int SDLK_ESCAPE = 0x1B;
	public static final int SDLK_BACKSPACE = 0x08;
	public static final int SDLK_TAB = 0x09;
	public static final int SDLK_DELETE = 0x7F;
	public static final int SDLK_RIGHT = 0x4000004F;
	public static final int SDLK_LEFT = 0x40000050;
	public static final int SDLK_DOWN = 0x40000051;
	public static final int SDLK_UP = 0x40000052;
	public static final int SDLK_KP_ENTER = 0x40000058;

	public static final int SDL_KMOD_SHIFT = 0x0003;
	public static final int SDL_KMOD_CTRL = 0x00C0;
	public static final int SDL_KMOD_ALT = 0x0300;
	public static final int SDL_KMOD_GUI = 0x0C00;
	public static final int SDL_KMOD_NUM = 0x1000;
	public static final int SDL_KMOD_CAPS = 0x2000;
}
