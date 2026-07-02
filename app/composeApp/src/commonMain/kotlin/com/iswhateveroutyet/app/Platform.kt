package com.iswhateveroutyet.app

/**
 * Whether this target is driven by touch. Gates the pull-to-refresh gesture container —
 * on desktop the Material indicator pokes out under the mouse wheel and there's no pull
 * gesture anyway (the settings sheet has a Refresh row instead).
 */
expect val isTouchPlatform: Boolean
