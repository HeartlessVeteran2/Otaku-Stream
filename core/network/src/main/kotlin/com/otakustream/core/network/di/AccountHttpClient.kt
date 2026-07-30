package com.otakustream.core.network.di

import javax.inject.Qualifier

// The client used for talking to the user's own accounts — AniList and Stremio.
//
// It exists so that traffic carrying the user's credentials does not share a cookie jar or a
// response cache with traffic to hosts that installed sources choose. Those are two different trust
// levels sharing one object today, and the sharing is not hypothetical: the Cloudflare interceptor
// takes cookies out of a challenge WebView and writes them into that jar, and the jar is
// ACCEPT_ALL — correct for third-party streaming hosts, and not something the account session
// should be sitting next to.
//
// The unqualified OkHttpClient is deliberately the *untrusted* one, so a new call site that forgets
// this qualifier gets the restricted client rather than the account one. Failing in the safe
// direction matters more here than convenience.
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AccountHttpClient
