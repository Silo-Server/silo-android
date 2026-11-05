package org.siloserver.silo.common.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionAccountChoiceTest {
    private val home = CompanionPairingServer(
        id = "home",
        url = "https://home.example",
        displayName = "Home",
        isActive = true,
        accounts = listOf(
            CompanionPairingAccount("alex", "alex", isCurrent = true),
            CompanionPairingAccount("sam", "sam"),
        ),
    )
    private val work = CompanionPairingServer(
        id = "work",
        url = "https://work.example",
        displayName = "Work",
        accounts = listOf(
            CompanionPairingAccount("wkim", "wkim", isCurrent = true, needsSignIn = true),
            CompanionPairingAccount("wsam", "wsam"),
        ),
    )
    private val alex = "home/alex"
    private val sam = "home/sam"
    private val wkim = "work/wkim"
    private val wsam = "work/wsam"

    private fun choice(mode: CompanionSelectionMode, limit: Int? = null) =
        CompanionAccountChoice("Living Room", listOf(home, work), mode, limit)

    @Test
    fun onlyThePhonesOwnAccountStartsSelectedAndExpiredOnesCantBePicked() {
        val choice = choice(CompanionSelectionMode.Several)
        assertEquals(setOf(alex), choice.initialSelection())
        assertFalse(choice.isEnabled(setOf(alex), wkim))
        assertEquals(setOf(alex), choice.toggle(setOf(alex), wkim))
        assertEquals(setOf(alex, sam, wsam), choice.toggle(choice.toggle(setOf(alex), sam), wsam))
        // A row that can't approve is never sent, even if asked for.
        assertEquals(listOf(alex), choice.resolve(setOf(alex, wkim)).map { it.key })
    }

    @Test
    fun thePhonesAccountIsSentFirstThenListOrder() {
        val choice = choice(CompanionSelectionMode.Several)
        assertEquals(listOf(alex, sam, wsam), choice.resolve(setOf(wsam, sam, alex)).map { it.key })
        val awayFromHome = CompanionAccountChoice(
            "Living Room",
            listOf(home.copy(isActive = false), work.copy(isActive = true, accounts = work.accounts.map { it.copy(needsSignIn = false) })),
            CompanionSelectionMode.Several,
        )
        assertEquals(listOf(wkim, alex, wsam), awayFromHome.resolve(setOf(alex, wsam, wkim)).map { it.key })
    }

    @Test
    fun anOlderTvTakesOneAccountPerServer() {
        val choice = choice(CompanionSelectionMode.OnePerServer)
        assertEquals(setOf(sam), choice.toggle(setOf(alex), sam))
        assertEquals(setOf(alex, wsam), choice.toggle(setOf(alex), wsam))
        assertEquals(listOf(alex, wsam), choice.resolve(setOf(alex, sam, wsam)).map { it.key })
    }

    @Test
    fun anOlderRokuTakesOneAccountInAll() {
        val choice = choice(CompanionSelectionMode.One)
        assertEquals(setOf(wsam), choice.toggle(setOf(alex), wsam))
        // Tapping the chosen row keeps it: a Roku always gets one.
        assertEquals(setOf(alex), choice.toggle(setOf(alex), alex))
        assertEquals(listOf(alex), choice.resolve(setOf(alex, sam)).map { it.key })
    }

    @Test
    fun aLimitDisablesFurtherRowsOnceReached() {
        val choice = choice(CompanionSelectionMode.Several, limit = 2)
        val two = choice.toggle(setOf(alex), sam)
        assertTrue(choice.limitReached(two))
        assertFalse(choice.isEnabled(two, wsam))
        assertTrue(choice.isEnabled(two, sam))
        assertEquals(two, choice.toggle(two, wsam))
        assertEquals(2, choice.resolve(setOf(alex, sam, wsam)).size)
    }

    @Test
    fun aServerWithoutSavedAccountsOffersItsCurrentOne() {
        val legacy = CompanionPairingServer("old", "https://old.example", "Old", isActive = true)
        val choice = CompanionAccountChoice("Living Room", listOf(legacy), CompanionSelectionMode.Several)
        assertEquals(setOf("old/"), choice.initialSelection())
        assertEquals(null, choice.resolve(setOf("old/")).single().account.id)
    }

    @Test
    fun txtKeysChooseTheMode() {
        val tv = CompanionPairingTarget("tv", "TV", "h", 1)
        assertEquals(CompanionSelectionMode.OnePerServer, tv.selectionMode)
        assertEquals(CompanionSelectionMode.Several, tv.copy(acceptsAccounts = true).selectionMode)
        assertEquals(CompanionSelectionMode.One, tv.copy(isRoku = true).selectionMode)
        assertEquals(CompanionSelectionMode.Several, tv.copy(isRoku = true, acceptsAccounts = true).selectionMode)
        val parsed = pairingTargetFromRecord("10.0.0.2", 9000, "Den", mapOf("acct" to "1", "lim" to "3"), isRoku = true)!!
        assertTrue(parsed.acceptsAccounts)
        assertEquals(3, parsed.signInLimit)
        val old = pairingTargetFromRecord("10.0.0.2", 9000, "Den", mapOf("lim" to "x"), isRoku = false)!!
        assertFalse(old.acceptsAccounts)
        assertEquals(null, old.signInLimit)
    }
}
