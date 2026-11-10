package org.siloserver.silo.android.ui.screens.auth

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.common.i18n.uiText
import org.siloserver.silo.model.auth.SignInProvider

/** "Sign in with <provider>". */
fun signInWithLabel(provider: SignInProvider): UiText = uiText(CommonR.string.signIn_withProvider, provider.displayName)

/**
 * A network provider's button: "Continue as <owner>" when the provider named
 * who owns this device, "Continue with <provider>" when it named nobody.
 */
fun continueAsLabel(provider: SignInProvider): UiText =
    provider.networkIdentity?.label?.let { uiText(CommonR.string.tvSignIn_network_continueAs, it) }
        ?: uiText(CommonR.string.tvSignIn_network_continueWith, provider.displayName)

/** The line under "Continue as <owner>" naming the provider; none when the label already names it. */
fun continueViaLabel(provider: SignInProvider): UiText? =
    provider.networkIdentity?.label?.let { uiText(CommonR.string.tvSignIn_network_via, provider.displayName) }

/**
 * The account-choice link under the provider buttons. It picks another
 * account at the provider, not another way into Silo, so it names the
 * provider when there is one; with several, a chooser follows.
 */
fun differentAccountLabel(providers: List<SignInProvider>): UiText =
    providers.singleOrNull()?.let { uiText(CommonR.string.signIn_differentProviderAccount, it.displayName) }
        ?: uiText(CommonR.string.signIn_differentAccount)
