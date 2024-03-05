/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.identity.mdoc.mso

import com.android.identity.cbor.Cbor
import com.android.identity.cbor.CborArray
import com.android.identity.cbor.DataItem
import com.android.identity.crypto.EcPublicKey
import com.android.identity.util.Timestamp

/**
 * Helper class for parsing the bytes of `MobileSecurityObject`
 * [CBOR](http://cbor.io/)
 * as specified in *ISO/IEC 18013-5* section 9.1.2 *Issuer data authentication*.
 *
 * @param encodedMobileSecurityObject The bytes of `MobileSecurityObject`.
 */
class MobileSecurityObjectParser(
    private var encodedMobileSecurityObject: ByteArray
) {
    /**
     * Parses the mobile security object.
     *
     * @return a [MobileSecurityObject] with the parsed data.
     * @exception IllegalArgumentException if the given data isn't valid CBOR or not conforming
     * to the CDDL for its type.
     * @exception IllegalStateException if required data hasn't been set using the setter
     * methods on this class.
     */
    fun parse(): MobileSecurityObject {
        val mso = MobileSecurityObject()
        mso.parse(encodedMobileSecurityObject)
        return mso
    }

    /**
     * An object used to represent data parsed from `MobileSecurityObject`
     * [CBOR](http://cbor.io/)
     * as specified in *ISO/IEC 18013-5* section 9.1.2 *Issuer data authentication*
     */
    class MobileSecurityObject internal constructor() {
        private lateinit var valueDigests: MutableMap<String, Map<Long, ByteArray>>
        private var _authorizedNameSpaces: MutableList<String>? = null
        private var _authorizedDataElements: MutableMap<String, List<String>>? = null
        private var _deviceKeyInfo: MutableMap<Long, ByteArray>? = null

        /**
         * The version string set in the `MobileSecurityObject` CBOR.
         */
        lateinit var version: String

        /**
         * The digest algorithm set in the `MobileSecurityObject` CBOR.
         */
        lateinit var digestAlgorithm: String

        /**
         * The document type set in the `MobileSecurityObject` CBOR.
         */
        lateinit var docType: String

        /**
         * The mdoc authentication public key set in the `MobileSecurityObject` CBOR.
         */
        lateinit var deviceKey: EcPublicKey

        /**
         * The timestamp at which the MSO signature was created, as set in the
         * `MobileSecurityObject` CBOR.
         */
        lateinit var signed: Timestamp

        /**
         * The timestamp before which the MSO is not yet valid, as set in the
         * `MobileSecurityObject` CBOR.
         */
        lateinit var validFrom: Timestamp

        /**
         * The timestamp after which the MSO is no longer valid, as set in the
         * `MobileSecurityObject` CBOR.
         */
        lateinit var validUntil: Timestamp

        /**
         * The timestamp at which the issuing authority infrastructure expects to re-sign the
         * MSO, if provided in the `MobileSecurityObject` CBOR, else null.
         */
        var expectedUpdate: Timestamp? = null

        /**
         * The set of namespaces provided in the ValueDigests map within the
         * `MobileSecurityObject` CBOR.
         */
        val valueDigestNamespaces: Set<String>
            get() = valueDigests.keys

        /**
         * Gets a non-empty mapping between a `DigestID` and a `Digest` for a
         * particular namespace, as set in the ValueDigests map within the
         * `MobileSecurityObject` CBOR.
         *
         * @param namespace The namespace of interest.
         * @return The mapping present for that namespace if it exists within the ValueDigests,
         * else null.
         */
        fun getDigestIDs(namespace: String): Map<Long, ByteArray>? {
            return valueDigests[namespace]
        }

        /**
         * Gets the `AuthorizedNameSpaces` portion of the `keyAuthorizations`
         * within `DeviceKeyInfo`. Is null if it does not exist in the MSO.
         */
        val deviceKeyAuthorizedNameSpaces: List<String>?
            get() = _authorizedNameSpaces

        /**
         * The `AuthorizedDataElements` portion of the `keyAuthorizations`
         * within `DeviceKeyInfo`. Is null if it does not exist in the MSO.
         */
        val deviceKeyAuthorizedDataElements: Map<String, List<String>>?
            get() = _authorizedDataElements

        /**
         * Gets extra info for the mdoc authentication public key as part of the
         * `KeyInfo` portion of the `DeviceKeyInfo`. Is null if it does not exist in the MSO.
         */
        val deviceKeyInfo: Map<Long, ByteArray>?
            get() = _deviceKeyInfo

        private fun parseValueDigests(valueDigests: DataItem) {
            this.valueDigests = HashMap()
            for (namespaceDataItem in valueDigests.asMap.keys) {
                val namespace = namespaceDataItem.asTstr
                val digestIDsDataItem = valueDigests.asMap[namespaceDataItem]
                val digestIDs: MutableMap<Long, ByteArray> = HashMap()
                for (digestIDDataItem in digestIDsDataItem!!.asMap.keys) {
                    val digestID = digestIDDataItem.asNumber
                    digestIDs[digestID] = digestIDsDataItem[digestID].asBstr
                }
                this.valueDigests[namespace] = digestIDs
            }
        }

        private fun parseDeviceKeyInfo(deviceKeyInfo: DataItem) {
            deviceKey = deviceKeyInfo["deviceKey"].asCoseKey.ecPublicKey
            _authorizedNameSpaces = null
            _authorizedDataElements = null
            val keyAuth = deviceKeyInfo.getOrNull("keyAuthorizations")
            if (keyAuth != null) {
                val nameSpaces = keyAuth.getOrNull("nameSpaces")
                if (nameSpaces != null) {
                    _authorizedNameSpaces = mutableListOf()
                    for (nameSpaceDataItem in (nameSpaces as CborArray).items) {
                        _authorizedNameSpaces!!.add(nameSpaceDataItem.asTstr)
                    }
                }
                val dataElements = keyAuth.getOrNull("dataElements")
                if (dataElements != null) {
                    _authorizedDataElements = mutableMapOf()
                    for (nameSpaceNameDataItem in dataElements.asMap.keys) {
                        val nameSpaceName = nameSpaceNameDataItem.asTstr
                        val dataElemArray: MutableList<String> = ArrayList()
                        val dataElementDataItems = dataElements[nameSpaceName] as CborArray
                        for (dataElementIdentifier in dataElementDataItems.items) {
                            dataElemArray.add(dataElementIdentifier.asTstr)
                        }
                        _authorizedDataElements!![nameSpaceName] = dataElemArray
                    }
                }
            }
            _deviceKeyInfo = null
            if (deviceKeyInfo.getOrNull("keyInfo") != null) {
                _deviceKeyInfo = mutableMapOf()
                val keyInfo = deviceKeyInfo["keyInfo"]
                for (keyInfoKeyDataItem in keyInfo.asMap.keys) {
                    val keyInfoKey = keyInfoKeyDataItem.asNumber
                    _deviceKeyInfo!![keyInfoKey] = keyInfo[keyInfoKeyDataItem].asBstr
                }
            }
        }

        private fun parseValidityInfo(validityInfo: DataItem) {
            signed = Timestamp.ofEpochMilli(validityInfo["signed"].asDateTimeString
                .toEpochMilliseconds())
            validFrom =
                Timestamp.ofEpochMilli(validityInfo["validFrom"].asDateTimeString.toEpochMilliseconds())
            validUntil =
                Timestamp.ofEpochMilli(validityInfo["validUntil"].asDateTimeString.toEpochMilliseconds())
            if (validityInfo.getOrNull("expectedUpdate") != null) {
                expectedUpdate =
                    Timestamp.ofEpochMilli(validityInfo["expectedUpdate"].asDateTimeString.toEpochMilliseconds())
            } else {
                expectedUpdate = null
            }
            require(validFrom.toEpochMilli() >= signed.toEpochMilli()) {
                "The validFrom timestamp should be equal or later than the signed timestamp"
            }
            require(validUntil.toEpochMilli() > validFrom.toEpochMilli()) {
                "The validUntil timestamp should be later than the validFrom timestamp"
            }
        }

        fun parse(encodedMobileSecurityObject: ByteArray) {
            val mso = Cbor.decode(encodedMobileSecurityObject)
            version = mso["version"].asTstr
            require(version.compareTo("1.0") >= 0) {
                "Given version '$version' not >= '1.0'"
            }

            digestAlgorithm = mso["digestAlgorithm"].asTstr
            val allowableDigestAlgorithms: List<String> =
                mutableListOf("SHA-256", "SHA-384", "SHA-512")
            require(allowableDigestAlgorithms.contains(digestAlgorithm)) {
                "Given digest algorithm '" + digestAlgorithm +
                        "' one of " + allowableDigestAlgorithms
            }
            docType = mso["docType"].asTstr
            parseValueDigests(mso["valueDigests"])
            parseDeviceKeyInfo(mso["deviceKeyInfo"])
            parseValidityInfo(mso["validityInfo"])
        }
    }
}