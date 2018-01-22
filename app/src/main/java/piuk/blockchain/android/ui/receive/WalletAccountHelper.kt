package piuk.blockchain.android.ui.receive

import info.blockchain.wallet.coin.GenericMetadataAccount
import info.blockchain.wallet.payload.PayloadManager
import info.blockchain.wallet.payload.data.Account
import info.blockchain.wallet.payload.data.LegacyAddress
import org.web3j.utils.Convert
import piuk.blockchain.android.R
import piuk.blockchain.android.data.bitcoincash.BchDataManager
import piuk.blockchain.android.data.currency.CryptoCurrencies
import piuk.blockchain.android.data.currency.CurrencyState
import piuk.blockchain.android.data.ethereum.EthDataManager
import piuk.blockchain.android.ui.account.ItemAccount
import piuk.blockchain.android.util.ExchangeRateFactory
import piuk.blockchain.android.util.MonetaryUtil
import piuk.blockchain.android.util.PrefsUtil
import piuk.blockchain.android.util.StringUtils
import piuk.blockchain.android.util.annotations.Mockable
import piuk.blockchain.android.util.helperfunctions.unsafeLazy
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.util.*

@Mockable
class WalletAccountHelper(
        private val payloadManager: PayloadManager,
        private val stringUtils: StringUtils,
        private val prefsUtil: PrefsUtil,
        private val exchangeRateFactory: ExchangeRateFactory,
        private val currencyState: CurrencyState,
        private val ethDataManager: EthDataManager,
        private val bchDataManager: BchDataManager
) {
    private val btcUnitType: Int by unsafeLazy {
        prefsUtil.getValue(
                PrefsUtil.KEY_BTC_UNITS,
                MonetaryUtil.UNIT_BTC
        )
    }
    private val monetaryUtil: MonetaryUtil by unsafeLazy { MonetaryUtil(btcUnitType) }
    private val btcUnit: String by unsafeLazy { monetaryUtil.getBtcUnit(btcUnitType) }
    private val bchUnit: String by unsafeLazy { monetaryUtil.getBchUnit(btcUnitType) }
    private val fiatUnit: String by unsafeLazy {
        prefsUtil.getValue(
                PrefsUtil.KEY_SELECTED_FIAT,
                PrefsUtil.DEFAULT_CURRENCY
        )
    }
    private val btcExchangeRate: Double by unsafeLazy { exchangeRateFactory.getLastBtcPrice(fiatUnit) }
    private val ethExchangeRate: Double by unsafeLazy { exchangeRateFactory.getLastEthPrice(fiatUnit) }

    /**
     * Returns a list of [ItemAccount] objects containing both HD accounts and [LegacyAddress]
     * objects, eg from importing accounts.
     *
     * @return Returns a list of [ItemAccount] objects
     */
    fun getAccountItems(): List<ItemAccount> = when (currencyState.cryptoCurrency) {
        CryptoCurrencies.BTC -> mutableListOf<ItemAccount>().apply {
            addAll(getHdAccounts())
            addAll(getLegacyAddresses())
        }.toList()
        CryptoCurrencies.BCH -> mutableListOf<ItemAccount>().apply {
            addAll(getHdBchAccounts())
            addAll(getLegacyBchAddresses())
        }
        else -> getEthAccount().toList()
    }

    /**
     * Returns a list of [ItemAccount] objects containing only HD accounts.
     *
     * @return Returns a list of [ItemAccount] objects
     */
    fun getHdAccounts(): List<ItemAccount> {
        val list =
            payloadManager.payload?.hdWallets?.get(0)?.accounts ?: Collections.emptyList<Account>()
        // Skip archived account
        return list.filterNot { it.isArchived }
            .map {
                ItemAccount(
                        it.label,
                        getAccountBalance(it, btcExchangeRate, fiatUnit, btcUnit),
                        null,
                        getAccountAbsoluteBalance(it),
                        it,
                        it.xpub
                )
            }
    }

    /**
     * Returns a list of [ItemAccount] objects containing only HD accounts.
     *
     * @return Returns a list of [ItemAccount] objects
     */
    fun getHdBchAccounts(): List<ItemAccount> {
        // Skip archived account
        return bchDataManager.getActiveAccounts().filterNot { it.isArchived }
            .map {
                ItemAccount(
                        it.label,
                        getAccountBalanceBch(it, btcExchangeRate, fiatUnit, bchUnit),
                        null,
                        getAccountAbsoluteBalance(it),
                        it,
                        it.xpub
                )
            }
    }

    /**
     * Returns a list of [ItemAccount] objects containing only [LegacyAddress] objects.
     *
     * @return Returns a list of [ItemAccount] objects
     */
    fun getLegacyAddresses() = payloadManager.payload.legacyAddressList
        // Skip archived address
        .filterNot { it.tag == LegacyAddress.ARCHIVED_ADDRESS }
        .map {
            // If address has no label, we'll display address
            var labelOrAddress: String? = it.label
            if (labelOrAddress == null || labelOrAddress.trim { it <= ' ' }.isEmpty()) {
                labelOrAddress = it.address
            }

            // Watch-only tag - we'll ask for xpriv scan when spending from
            var tag: String? = null
            if (it.isWatchOnly) {
                tag = stringUtils.getString(R.string.watch_only)
            }

            ItemAccount(
                    labelOrAddress,
                    getAddressBalance(it, btcExchangeRate, fiatUnit, btcUnit),
                    tag,
                    getAddressAbsoluteBalance(it),
                    it,
                    it.address
            )
        }

    /**
     * Returns a list of [ItemAccount] objects containing only [LegacyAddress] objects which also
     * have a BCH balance.
     *
     * @return Returns a list of [ItemAccount] objects
     */
    fun getLegacyBchAddresses() = payloadManager.payload.legacyAddressList
        // Skip archived address
        .filterNot { it.tag == LegacyAddress.ARCHIVED_ADDRESS }
        .filterNot {
            bchDataManager.getAddressBalance(it.address).compareTo(BigInteger.ZERO) == 0
        }
        .map {
            // If address has no label, we'll display address
            var labelOrAddress: String? = it.label
            if (labelOrAddress == null || labelOrAddress.trim { it <= ' ' }.isEmpty()) {
                labelOrAddress = it.address
            }

            // Watch-only tag - we'll ask for xpriv scan when spending from
            var tag: String? = null
            if (it.isWatchOnly) {
                tag = stringUtils.getString(R.string.watch_only)
            }

            ItemAccount(
                    labelOrAddress,
                    getBchAddressBalance(it, btcExchangeRate, fiatUnit, bchUnit),
                    tag,
                    getAddressAbsoluteBalance(it),
                    it,
                    it.address
            )
        }

    /**
     * Returns a list of [ItemAccount] objects containing only [LegacyAddress] objects,
     * specifically from the list of address book entries.
     *
     * @return Returns a list of [ItemAccount] objects
     */
    fun getAddressBookEntries() = payloadManager.payload.addressBook?.map {
        ItemAccount(
                if (it.label.isNullOrEmpty()) it.address else it.label,
                "",
                stringUtils.getString(R.string.address_book_label),
                null,
                null,
                it.address
        )
    } ?: emptyList()

    fun getDefaultAccount(): ItemAccount = when (currencyState.cryptoCurrency) {
        CryptoCurrencies.BTC -> getDefaultBtcAccount()
        CryptoCurrencies.BCH -> getDefaultBchAccount()
        CryptoCurrencies.ETHER -> getDefaultEthAccount()
        else -> throw IllegalArgumentException("Cryptocurrency ${currencyState.cryptoCurrency.unit} not yet supported")
    }

    fun getEthAccount() = mutableListOf<ItemAccount>().apply {
        add(getDefaultEthAccount())
    }

    /**
     * Returns the balance of an [Account] in Satoshis (BTC)
     */
    private fun getAccountAbsoluteBalance(account: Account) =
        payloadManager.getAddressBalance(account.xpub).toLong()

    /**
     * Returns the balance of a [GenericMetadataAccount] in Satoshis (BCH)
     */
    private fun getAccountAbsoluteBalance(account: GenericMetadataAccount) =
            bchDataManager.getAddressBalance(account.xpub).toLong()

    /**
     * Returns the balance of an [Account], formatted for display.
     */
    private fun getAccountBalance(
            account: Account,
            btcExchange: Double,
            fiatUnit: String,
            btcUnit: String
    ): String {

        val btcBalance = getAccountAbsoluteBalance(account)

        return if (!currencyState.isDisplayingCryptoCurrency) {
            val fiatBalance = btcExchange * (btcBalance / 1e8)
            "(${monetaryUtil.getFiatFormat(fiatUnit).format(fiatBalance)} $fiatUnit)"
        } else {
            "(${monetaryUtil.getDisplayAmount(btcBalance)} $btcUnit)"
        }
    }

    /**
     * Returns the balance of a [GenericMetadataAccount], formatted for display.
     */
    private fun getAccountBalanceBch(
            account: GenericMetadataAccount,
            bchExchange: Double,
            fiatUnit: String,
            bchUnit: String
    ): String {

        val bchBalance = getAccountAbsoluteBalance(account)

        return if (!currencyState.isDisplayingCryptoCurrency) {
            val fiatBalance = bchExchange * (bchBalance / 1e8)
            "(${monetaryUtil.getFiatFormat(fiatUnit).format(fiatBalance)} $fiatUnit)"
        } else {
            "(${monetaryUtil.getDisplayAmount(bchBalance)} $bchUnit)"
        }
    }

    /**
     * Returns the balance of a [LegacyAddress] in Satoshis
     */
    private fun getAddressAbsoluteBalance(legacyAddress: LegacyAddress) =
        payloadManager.getAddressBalance(legacyAddress.address).toLong()

    /**
     * Returns the balance of a [LegacyAddress] in Satoshis
     */
    private fun getBchAddressAbsoluteBalance(legacyAddress: LegacyAddress) =
            bchDataManager.getAddressBalance(legacyAddress.address).toLong()

    /**
     * Returns the balance of a [LegacyAddress], formatted for display
     */
    private fun getAddressBalance(
            legacyAddress: LegacyAddress,
            btcExchange: Double,
            fiatUnit: String,
            btcUnit: String
    ): String {

        val btcBalance = getAddressAbsoluteBalance(legacyAddress)

        return if (!currencyState.isDisplayingCryptoCurrency) {
            val fiatBalance = btcExchange * (btcBalance / 1e8)
            "(${monetaryUtil.getFiatFormat(fiatUnit).format(fiatBalance)} $fiatUnit)"
        } else {
            "(${monetaryUtil.getDisplayAmount(btcBalance)} $btcUnit)"
        }
    }

    /**
     * Returns the balance of a [LegacyAddress] in BCH, formatted for display
     */
    private fun getBchAddressBalance(
            legacyAddress: LegacyAddress,
            btcExchange: Double,
            fiatUnit: String,
            bchUnit: String
    ): String {

        val btcBalance = getBchAddressAbsoluteBalance(legacyAddress)

        return if (!currencyState.isDisplayingCryptoCurrency) {
            val fiatBalance = btcExchange * (btcBalance / 1e8)
            "(${monetaryUtil.getFiatFormat(fiatUnit).format(fiatBalance)} $fiatUnit)"
        } else {
            "(${monetaryUtil.getDisplayAmount(btcBalance)} $bchUnit)"
        }
    }

    private fun getDefaultBtcAccount(): ItemAccount {
        val account =
            payloadManager.payload.hdWallets[0].accounts[payloadManager.payload.hdWallets[0].defaultAccountIdx]
        return ItemAccount(
                account.label,
                getAccountBalance(account, btcExchangeRate, fiatUnit, btcUnit),
                null,
                getAccountAbsoluteBalance(account),
                account,
                account.xpub
        )
    }

    private fun getDefaultBchAccount(): ItemAccount {
        val account = bchDataManager.getDefaultGenericMetadataAccount()!!
        return ItemAccount(
                account.label,
                getAccountBalanceBch(account, btcExchangeRate, fiatUnit, btcUnit),
                null,
                getAccountAbsoluteBalance(account),
                account,
                account.xpub
        )
    }

    private fun getDefaultEthAccount(): ItemAccount {
        val ethModel = ethDataManager.getEthResponseModel()
        val ethAccount = ethDataManager.getEthWallet()!!.account
        val amount = Convert.fromWei(ethModel?.getTotalBalance().toString(), Convert.Unit.ETHER)
        amount.setScale(8, RoundingMode.HALF_DOWN)

        val displayString = if (currencyState.isDisplayingCryptoCurrency) {
            val numberFormat = DecimalFormat.getInstance().apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 8
            }

            "(${numberFormat.format(amount)} ETH)"
        } else {
            val fiatBalance = amount.multiply(BigDecimal.valueOf(ethExchangeRate))
            "(${monetaryUtil.getFiatFormat(fiatUnit).format(fiatBalance)} $fiatUnit)"
        }

        return ItemAccount(
                ethAccount?.label,
                displayString,
                null,
                0,
                ethAccount,
                ethAccount?.address
        )
    }

}
