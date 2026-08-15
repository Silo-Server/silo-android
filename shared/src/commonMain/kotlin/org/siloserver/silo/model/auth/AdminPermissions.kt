package org.siloserver.silo.model.auth

import org.siloserver.silo.model.profile.Profile

/** Admin role wire value (server `user.role`). */
const val ADMIN_ROLE = "admin"

/**
 * Client mirror of the server's `RequireActingAdmin` gate (web
 * `isActingAdmin(user, profile)`): the account role must be admin AND the
 * active household profile must be the primary (owner) profile.
 *
 * Fails CLOSED on an unresolved profile. A null [profile] used to be read as
 * "not yet loaded" and granted admin to an admin account, on the reasoning that
 * the surface is gated server-side anyway. But the account role is the same on
 * every profile in the household, so the profile is the ONLY thing separating
 * the owner from a child profile — and every path that could not resolve it
 * showed the admin surface on profiles that must never see it. A settings load
 * that merely failed was enough.
 *
 * Withholding it is recoverable in a way showing it wrongly is not — but only
 * because the call sites retry a profile that has not resolved. They are NOT
 * reactive: nothing here observes the profile, so a caller that evaluates this
 * once and never asks again will hold a false answer for its own lifetime. Any
 * new call site has to retry or observe, or it will hide the surface from a
 * genuine owner.
 *
 * A null [user] is never acting-admin.
 */
fun isActingAdmin(user: User?, profile: Profile?): Boolean =
    user?.role == ADMIN_ROLE && profile?.isPrimary == true

/**
 * Client mirror of the server's profile-management gate as seen from the
 * profile picker ("Who's watching?").
 *
 * The server authorizes `POST /profiles`, `PUT /profiles/{id}` and
 * `DELETE /profiles/{id}` when the caller's ACTIVE profile is the household
 * primary, OR the account role is admin — both arms are mirrored here.
 * Which arm can hold at the picker depends on how the app got there:
 *
 *  - Phone keeps the acting profile across "Switch Profile", so a non-admin
 *    household owner acting as the primary profile is fully authorized at
 *    the picker ([activeProfile] is the primary).
 *  - TV clears the active profile before showing the picker (and at first
 *    login nothing is selected on either platform), so [activeProfile] is
 *    null there and only the admin arm can hold. A management call from any
 *    other account with no acting profile can only end in a 403 ("Profile
 *    management requires the primary profile or admin access").
 *
 * Creation has one exemption this predicate deliberately does not cover:
 * bootstrap of the very FIRST profile (the server allows `POST /profiles`
 * when the account has none). Call sites keep the add affordance visible for
 * an empty profile list for that reason.
 *
 * Fails CLOSED when neither input resolves, like [isActingAdmin] — call
 * sites must re-evaluate once they do (the pickers reload on every resume).
 */
fun canManageProfilesFromPicker(user: User?, activeProfile: Profile?): Boolean =
    user?.role == ADMIN_ROLE || activeProfile?.isPrimary == true
