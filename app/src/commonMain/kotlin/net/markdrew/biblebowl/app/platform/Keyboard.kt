package net.markdrew.biblebowl.app.platform

/**
 * Whether to offer keyboard shortcuts (and the keycap hints advertising them). True on desktop;
 * false on Android, whose users are on a touch screen.
 */
expect val hasHardwareKeyboard: Boolean
