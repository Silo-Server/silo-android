package org.siloserver.silo.android.ui.screens.auth

import org.siloserver.silo.model.auth.SignInProvider

/** "Sign in with <provider>". */
fun signInWithLabel(provider: SignInProvider): String = "Sign in with ${provider.displayName}"

/**
 * A network provider's button: "Continue as <owner>" when the provider named
 * who owns this device, "Continue with <provider>" when it named nobody.
 */
fun continueAsLabel(provider: SignInProvider): String =
    provider.networkIdentity?.label?.let { "Continue as $it" } ?: "Continue with ${provider.displayName}"

/** The line under "Continue as <owner>" naming the provider; none when the label already names it. */
fun continueViaLabel(provider: SignInProvider): String? =
    provider.networkIdentity?.label?.let { "via ${provider.displayName}" }

/**
 * The account-choice link under the provider buttons. It picks another
 * account at the provider, not another way into Silo, so it names the
 * provider when there is one; with several, a chooser follows.
 */
fun differentAccountLabel(providers: List<SignInProvider>): String =
    providers.singleOrNull()?.let { "Use a different ${it.displayName} account" } ?: "Use a different account"
