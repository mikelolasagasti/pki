package com.netscape.kra;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import java.util.Hashtable;

import javax.crypto.spec.RC2ParameterSpec;

import org.dogtagpki.server.kra.KRAEngine;
import org.dogtagpki.server.kra.KRAEngineConfig;
import org.dogtagpki.server.kra.rest.KeyRequestService;
import org.mozilla.jss.asn1.OBJECT_IDENTIFIER;
import org.mozilla.jss.asn1.OCTET_STRING;
import org.mozilla.jss.crypto.CryptoToken;
import org.mozilla.jss.crypto.EncryptionAlgorithm;
import org.mozilla.jss.crypto.IVParameterSpec;
import org.mozilla.jss.crypto.KeyGenerator;
import org.mozilla.jss.crypto.KeyWrapAlgorithm;
import org.mozilla.jss.crypto.PBEAlgorithm;
import org.mozilla.jss.crypto.PBEKeyGenParams;
import org.mozilla.jss.crypto.PrivateKey;
import org.mozilla.jss.crypto.SymmetricKey;
import org.mozilla.jss.netscape.security.util.DerValue;
import org.mozilla.jss.netscape.security.util.Utils;
import org.mozilla.jss.netscape.security.util.WrappingParams;
import org.mozilla.jss.netscape.security.x509.X509Key;
import org.mozilla.jss.pkcs12.PasswordConverter;
import org.mozilla.jss.pkcs7.ContentInfo;
import org.mozilla.jss.pkcs7.EncryptedContentInfo;
import org.mozilla.jss.pkix.primitive.AlgorithmIdentifier;
import org.mozilla.jss.pkix.primitive.PBEParameter;
import org.mozilla.jss.util.Password;

import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.dbs.keydb.KeyId;
import com.netscape.certsrv.key.KeyRequestResource;
import com.netscape.certsrv.kra.EKRAException;
import com.netscape.certsrv.logging.event.SecurityDataArchivalProcessedEvent;
import com.netscape.certsrv.request.RequestId;
import com.netscape.certsrv.security.IStorageKeyUnit;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.dbs.KeyRecord;
import com.netscape.cmscore.dbs.KeyRepository;
import com.netscape.cmscore.logging.Auditor;
import com.netscape.cmscore.request.Request;
import com.netscape.cmscore.security.JssSubsystem;
import com.netscape.cmsutil.crypto.CryptoUtil;

public class SecurityDataProcessor {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SecurityDataProcessor.class);

    public final static String ATTR_KEY_RECORD = "keyRecord";
    public static final String ATTR_SERIALNO = "serialNumber";
    private final static String STATUS_ACTIVE = "active";

    private KeyRecoveryAuthority kra;
    private TransportKeyUnit transportUnit;
    private IStorageKeyUnit storageUnit = null;
    private KeyRepository keyRepository = null;

    private boolean useOAEPKeyWrap = false;

    private static boolean allowEncDecrypt_archival = false;
    private static boolean allowEncDecrypt_recovery = false;

    public SecurityDataProcessor(KeyRecoveryAuthority kra) {
        this.kra = kra;
        transportUnit = kra.getTransportKeyUnit();
        storageUnit = kra.getStorageKeyUnit();
        KRAEngine engine = KRAEngine.getInstance();
        keyRepository = engine.getKeyRepository();
    }

    public boolean archive(Request request)
            throws EBaseException {
        RequestId requestId = request.getRequestId();
        String clientKeyId = request.getExtDataInString(Request.SECURITY_DATA_CLIENT_KEY_ID);

        // one way to get data - unexploded pkiArchiveOptions
        String pkiArchiveOptions = request.getExtDataInString(Request.REQUEST_ARCHIVE_OPTIONS);

        // another way - exploded pkiArchiveOptions
        String transWrappedSessionKey = request.getExtDataInString(Request.REQUEST_SESSION_KEY);
        String wrappedSecurityData = request.getExtDataInString(Request.REQUEST_SECURITY_DATA);
        String algParams = request.getExtDataInString(Request.REQUEST_ALGORITHM_PARAMS);
        String algStr = request.getExtDataInString(Request.REQUEST_ALGORITHM_OID);

        // parameters if the secret is a symmetric key
        String dataType = request.getExtDataInString(Request.SECURITY_DATA_TYPE);
        String algorithm = request.getExtDataInString(Request.SECURITY_DATA_ALGORITHM);
        int strength = request.getExtDataInInteger(Request.SECURITY_DATA_STRENGTH);

        // parameter for realm
        String realm = request.getRealm();

        logger.debug("SecurityDataProcessor.archive. Request id: " + requestId);
        logger.debug("SecurityDataProcessor.archive wrappedSecurityData: " + wrappedSecurityData);

        KRAEngine engine = KRAEngine.getInstance();
        JssSubsystem jssSubsystem = engine.getJSSSubsystem();

        KRAEngineConfig config = null;

        try {
            config = engine.getConfig();
            allowEncDecrypt_archival = config.getBoolean("kra.allowEncDecrypt.archival", false);
            useOAEPKeyWrap = config.getUseOAEPKeyWrap();
        } catch (Exception e) {
            throw new EBaseException(CMS.getUserMessage("CMS_BASE_CERT_ERROR", e.toString()));
        }


        String owner = request.getExtDataInString(Request.ATTR_REQUEST_OWNER);

        Auditor auditor = engine.getAuditor();
        String auditSubjectID = owner;

        //Check here even though restful layer checks for this.
        if (clientKeyId == null || dataType == null) {

            auditor.log(SecurityDataArchivalProcessedEvent.createFailureEvent(
                    auditSubjectID,
                    null,
                    requestId,
                    clientKeyId,
                    null,
                    "Bad data in request",
                    null));

            throw new EBaseException("Bad data in SecurityDataService.serviceRequest");
        }

        if (wrappedSecurityData != null) {
            if (transWrappedSessionKey == null || algStr == null || algParams == null) {
                throw new EBaseException(
                        "Bad data in SecurityDataService.serviceRequest, no session key");

            }
        } else if (pkiArchiveOptions == null) {
            throw new EBaseException("No data to archive in SecurityDataService.serviceRequest");
        }

        byte[] wrappedSessionKey = null;
        byte[] secdata = null;
        byte[] sparams = null;

        if (wrappedSecurityData == null) {
            // We have PKIArchiveOptions data

            //We need some info from the PKIArchiveOptions wrapped security data
            byte[] encoded = Utils.base64decode(pkiArchiveOptions);

            ArchiveOptions options = ArchiveOptions.toArchiveOptions(encoded);
            algStr = options.getSymmAlgOID();
            wrappedSessionKey = options.getEncSymmKey();
            secdata = options.getEncValue();
            sparams = options.getSymmAlgParams();

        } else {
            wrappedSessionKey = Utils.base64decode(transWrappedSessionKey);
            secdata = Utils.base64decode(wrappedSecurityData);
            sparams = Utils.base64decode(algParams);
        }

        SymmetricKey securitySymKey = null;
        byte[] securityData = null;

        String keyType = null;
        byte [] tmp_unwrapped = null;
        byte [] unwrapped = null;
        if (dataType.equals(KeyRequestResource.SYMMETRIC_KEY_TYPE)) {
            // Symmetric Key
            keyType = KeyRequestResource.SYMMETRIC_KEY_TYPE;

            if (allowEncDecrypt_archival) {
                try {
                    tmp_unwrapped = transportUnit.decryptExternalPrivate(
                            wrappedSessionKey,
                            algStr,
                            sparams,
                            secdata,
                            null);

                } catch (Exception e) {
                    throw new EBaseException("Unable to decrypt symmetric key with kra.allowEncDecrypt.archival=true: " + e.getMessage(), e);
                }

                /* making sure leading 0's are removed */
                int first=0;
                for (int j=0; (j< tmp_unwrapped.length) && (tmp_unwrapped[j]==0); j++) {
                    first++;
                }

                unwrapped = Arrays.copyOfRange(tmp_unwrapped, first, tmp_unwrapped.length);
                jssSubsystem.obscureBytes(tmp_unwrapped);

            } else {
                try {
                    securitySymKey = transportUnit.unwrap_symmetric(
                            wrappedSessionKey,
                            algStr,
                            sparams,
                            secdata,
                            KeyRequestService.SYMKEY_TYPES.get(algorithm),
                            strength);
                } catch (Exception e) {
                    throw new EBaseException("Unable to decrypt symmetric key: " + e.getMessage(), e);
                }
            }

        } else if (dataType.equals(KeyRequestResource.PASS_PHRASE_TYPE)) {
            keyType = KeyRequestResource.PASS_PHRASE_TYPE;
            try {
                securityData = transportUnit.decryptExternalPrivate(
                        wrappedSessionKey,
                        algStr,
                        sparams,
                        secdata,
                        null);
            } catch (Exception e) {
                throw new EBaseException("Unable to decrypt passphrase: " + e.getMessage(), e);
            }

        }
        WrappingParams params = null;

        byte[] publicKey = null;
        byte privateSecurityData[] = null;
        boolean doEncrypt = false;

        try {
            params = storageUnit.getWrappingParams(allowEncDecrypt_archival);
            if (securitySymKey != null && unwrapped == null) {
                privateSecurityData = storageUnit.wrap(securitySymKey, params);
            } else if (unwrapped != null && allowEncDecrypt_archival == true) {
                privateSecurityData = storageUnit.encryptInternalPrivate(unwrapped, params);
                doEncrypt = true;
                logger.debug("allowEncDecrypt_archival of symmetric key.");
            } else if (securityData != null) {
                privateSecurityData = storageUnit.encryptInternalPrivate(securityData, params);
                doEncrypt = true;
            } else { // We have no data.

                auditor.log(SecurityDataArchivalProcessedEvent.createFailureEvent(
                        auditSubjectID,
                        null,
                        requestId,
                        clientKeyId,
                        null,
                        "Failed to create security data to archive",
                        null));

                throw new EBaseException("Failed to create security data to archive!");
            }
        } catch (Exception e) {
            logger.error("Failed to create security data to archive: " + e.getMessage(), e);

            auditor.log(SecurityDataArchivalProcessedEvent.createFailureEvent(
                    auditSubjectID,
                    null,
                    requestId,
                    clientKeyId,
                    null,
                    CMS.getUserMessage("CMS_KRA_INVALID_PRIVATE_KEY"),
                    null));

            throw new EBaseException(CMS.getUserMessage("CMS_KRA_INVALID_PRIVATE_KEY"));
        } finally {
            jssSubsystem.obscureBytes(securityData);
            jssSubsystem.obscureBytes(unwrapped);
        }

        // create key record
        // Note that in this case the owner is the same as the approving agent
        // because the archival request is made by the agent.
        // The algorithm used to generate the symmetric key (being stored as the secret)
        // is set in later in this method. (which is different  from the algStr variable
        // which is the algorithm used for encrypting the secret.)
        KeyRecord rec = new KeyRecord(null, publicKey,
                privateSecurityData, owner,
                null, owner);

        rec.set(KeyRecord.ATTR_CLIENT_ID, clientKeyId);

        //Now we need a serial number for our new key.

        if (rec.getSerialNumber() != null) {

            auditor.log(SecurityDataArchivalProcessedEvent.createFailureEvent(
                    auditSubjectID,
                    null,
                    requestId,
                    clientKeyId,
                    null,
                    CMS.getUserMessage("CMS_KRA_INVALID_STATE"),
                    null));

            throw new EBaseException(CMS.getUserMessage("CMS_KRA_INVALID_STATE"));
        }

        BigInteger serialNo = keyRepository.getNextSerialNumber();

        if (serialNo == null) {
            logger.error(CMS.getLogMessage("CMSCORE_KRA_GET_NEXT_SERIAL"));

            auditor.log(SecurityDataArchivalProcessedEvent.createFailureEvent(
                    auditSubjectID,
                    null,
                    requestId,
                    clientKeyId,
                    null,
                    "Failed to get  next Key ID",
                    null));

            throw new EBaseException(CMS.getUserMessage("CMS_KRA_INVALID_STATE"));
        }

        rec.set(KeyRecord.ATTR_ID, serialNo);
        rec.set(KeyRecord.ATTR_DATA_TYPE, keyType);
        rec.set(KeyRecord.ATTR_STATUS, STATUS_ACTIVE);

        if (dataType.equals(KeyRequestResource.SYMMETRIC_KEY_TYPE)) {
            rec.set(KeyRecord.ATTR_ALGORITHM, algorithm);
            rec.set(KeyRecord.ATTR_KEY_SIZE, strength);
        }

        if (realm != null) {
            rec.set(KeyRecord.ATTR_REALM,  realm);
        }

        try {
            rec.setWrappingParams(params, doEncrypt);
        } catch (Exception e) {
            logger.error("Unable to store wrapping parameters: " + e.getMessage(), e);

            auditor.log(SecurityDataArchivalProcessedEvent.createFailureEvent(
                    auditSubjectID,
                    null,
                    requestId,
                    clientKeyId,
                    null,
                    "Failed to store wrapping parameters",
                    null));

            throw new EBaseException(CMS.getUserMessage("CMS_KRA_INVALID_STATE"), e);
        }

        logger.debug("KRA adding Security Data key record " + serialNo);

        keyRepository.addKeyRecord(rec);

        auditor.log(SecurityDataArchivalProcessedEvent.createSuccessEvent(
                auditSubjectID,
                null,
                requestId,
                clientKeyId,
                new KeyId(serialNo),
                null));

        request.setExtData(ATTR_KEY_RECORD, serialNo);
        request.setExtData(Request.RESULT, Request.RES_SUCCESS);
        return true;
    }

    public boolean recover(Request request)
            throws EBaseException {

        logger.debug("SecurityDataService.recover(): start");

        KRAEngine engine = KRAEngine.getInstance();
        JssSubsystem jssSubsystem = engine.getJSSSubsystem();

        KRAEngineConfig config = null;

        try {
            config = engine.getConfig();
            allowEncDecrypt_recovery = config.getBoolean("kra.allowEncDecrypt.recovery", false);
            useOAEPKeyWrap = config.getUseOAEPKeyWrap();
        } catch (Exception e) {
            throw new EBaseException(CMS.getUserMessage("CMS_BASE_CERT_ERROR", e.toString()));
        }

        Hashtable<String, Object> params = kra.getVolatileRequest(
                request.getRequestId());
        KeyId keyId = new KeyId(request.getExtDataInBigInteger(ATTR_SERIALNO));
        request.setExtData(ATTR_KEY_RECORD, keyId.toBigInteger());

        if (params == null) {
            logger.error("SecurityDataProcessor.recover(): Can't get volatile params.");
            throw new EBaseException("Can't obtain volatile params!");
        }

        String transWrappedSessKeyStr = (String) params.get(Request.SECURITY_DATA_TRANS_SESS_KEY);
        byte[] wrappedSessKey = null;
        if (transWrappedSessKeyStr != null) {
            wrappedSessKey = Utils.base64decode(transWrappedSessKeyStr);
        }

        String sessWrappedPassPhraseStr = (String) params.get(Request.SECURITY_DATA_SESS_PASS_PHRASE);
        byte[] wrappedPassPhrase = null;
        if (sessWrappedPassPhraseStr != null) {
            wrappedPassPhrase = Utils.base64decode(sessWrappedPassPhraseStr);
        }

        if (transWrappedSessKeyStr == null && sessWrappedPassPhraseStr == null) {
            //We may be in recovery case where no params were initially submitted.
            logger.warn("SecurityDataProcessor.recover(): No params provided.");
            return false;
        }

        KeyRecord keyRecord = keyRepository.readKeyRecord(keyId.toBigInteger());

        String dataType = (String) keyRecord.get(KeyRecord.ATTR_DATA_TYPE);
        if (dataType == null) dataType = KeyRequestResource.ASYMMETRIC_KEY_TYPE;

        SymmetricKey unwrappedSess = null;
        SymmetricKey symKey = null;
        byte[] unwrappedSecData = null;
        PrivateKey privateKey = null;

        Boolean encrypted = keyRecord.isEncrypted();
        if (encrypted == null) {
            // must be an old key record
            // assume the value of allowEncDecrypt
            encrypted = allowEncDecrypt_recovery;
        }

        if (dataType.equals(KeyRequestResource.SYMMETRIC_KEY_TYPE)) {
            if (encrypted) {
                logger.debug("Recover symmetric key by decrypting as per allowEncDecrypt_recovery: true.");
                unwrappedSecData = recoverSecurityData(keyRecord);
            } else {
                symKey = recoverSymKey(keyRecord);
            }

        } else if (dataType.equals(KeyRequestResource.PASS_PHRASE_TYPE)) {
            unwrappedSecData = recoverSecurityData(keyRecord);
        } else if (dataType.equals(KeyRequestResource.ASYMMETRIC_KEY_TYPE)) {
            try {
                if (encrypted) {
                    logger.debug("Recover asymmetric key by decrypting as per allowEncDecrypt_recovery: true.");
                    unwrappedSecData = recoverSecurityData(keyRecord);
                } else {
                    byte[] publicKeyData = keyRecord.getPublicKeyData();
                    byte[] privateKeyData = keyRecord.getPrivateKeyData();

                    PublicKey publicKey = X509Key.parsePublicKey(new DerValue(publicKeyData));
                    privateKey = storageUnit.unwrap(
                            privateKeyData,
                            publicKey,
                            true,
                            keyRecord.getWrappingParams(storageUnit.getOldWrappingParams()));
                }

            } catch (Exception e) {
                throw new EBaseException("Cannot fetch the private key from the database.", e);
            }

        } else {
            throw new EBaseException("Invalid data type stored in the database.");
        }

        CryptoToken ct = transportUnit.getToken();

        String payloadEncryptOID = (String) params.get(Request.SECURITY_DATA_PL_ENCRYPTION_OID);
        String payloadWrapName = (String) params.get(Request.SECURITY_DATA_PL_WRAPPING_NAME);
        String transportKeyAlgo = transportUnit.getCertificate().getPublicKey().getAlgorithm();

        if (allowEncDecrypt_recovery) {
            if (payloadWrapName == null) {
                // assume old client
                payloadWrapName = "DES3/CBC/Pad";
            } else if (payloadWrapName.equals("AES KeyWrap/Padding") ||
                    payloadWrapName.equals("AES KeyWrap")) {
                // Some HSMs have not implemented AES-KW yet
                // Make sure we select an algorithm that is supported.
                payloadWrapName = "AES/CBC/PKCS5Padding";
            }
        }

        byte[] iv = null;
        byte[] iv_wrap = null;
        try {
            iv = generate_iv(
                    payloadEncryptOID,
                    transportUnit.getOldWrappingParams().getPayloadEncryptionAlgorithm());
            iv_wrap = generate_wrap_iv(
                    payloadWrapName,
                    transportUnit.getOldWrappingParams().getPayloadWrapAlgorithm());
        } catch (Exception e1) {
            jssSubsystem.obscureBytes(unwrappedSecData);
            throw new EBaseException("Failed to generate IV when wrapping secret", e1);
        }
        String ivStr = iv != null? Utils.base64encode(iv, false): null;
        String ivStr_wrap = iv_wrap != null ? Utils.base64encode(iv_wrap, false): null;

        WrappingParams wrapParams = null;
        if (payloadEncryptOID == null) {
            // talking to an old server, use 3DES
            wrapParams = transportUnit.getOldWrappingParams();
            wrapParams.setPayloadEncryptionIV(new IVParameterSpec(iv));
            wrapParams.setPayloadWrappingIV(new IVParameterSpec(iv_wrap));
        } else {
            try {
                wrapParams = new WrappingParams(
                    payloadEncryptOID,
                    payloadWrapName,
                    transportKeyAlgo,
                    iv != null? new IVParameterSpec(iv): null,
                    iv_wrap != null? new IVParameterSpec(iv_wrap): null);
            } catch (Exception e) {
                jssSubsystem.obscureBytes(unwrappedSecData);
                throw new EBaseException("Cannot generate wrapping params: " + e, e);
            }
        }

        logger.debug("keyWrap.useOAEP=" + useOAEPKeyWrap);
        logger.debug("wrapParams.getSkWrapAlgorithm()=" + wrapParams.getSkWrapAlgorithm());

        if (useOAEPKeyWrap && wrapParams.getSkWrapAlgorithm() == KeyWrapAlgorithm.RSA) {
            // We can't reliably distinguish between PKCS1v1.5 and OAEP at
            // the key algorithm level. So, if we're using OAEP by config,
            // update our algo here.
            //
            // Additionally... wrapParam's constructor assumes all RSA is
            // the same. By updating skWrapAlgorithm after construction, we
            // can override that flawed assumption and force the use of OAEP.
            logger.debug("Upgrading RSA SkWrapAlgorithm to RSA_OAEP: keyWrap.useOAEP=true");
            wrapParams.setSkWrapAlgorithm(KeyWrapAlgorithm.RSA_OAEP);
        }

        byte[] key_data = null;
        String pbeWrappedData = null;

        if (sessWrappedPassPhraseStr != null) {
            logger.debug("SecurityDataProcessor.recover(): secure retrieved data with tranport passphrase");
            byte[] unwrappedPass = null;
            Password pass = null;

            try {
                unwrappedSess = transportUnit.unwrap_session_key(ct, wrappedSessKey,
                        SymmetricKey.Usage.DECRYPT, wrapParams);

                unwrappedPass = CryptoUtil.decryptUsingSymmetricKey(
                        ct,
                        wrapParams.getPayloadEncryptionIV(),
                        wrappedPassPhrase,
                        unwrappedSess,
                        wrapParams.getPayloadEncryptionAlgorithm());

                char[] passChars = CryptoUtil.bytesToChars(unwrappedPass);
                pass = new Password(passChars);
                jssSubsystem.obscureChars(passChars);


                if (dataType.equals(KeyRequestResource.SYMMETRIC_KEY_TYPE)) {

                    logger.debug("SecurityDataProcessor.recover(): wrap or encrypt stored symmetric key with transport passphrase");
                    if (encrypted) {
                        logger.debug("SecurityDataProcessor.recover(): allowEncDecyypt_recovery: true, symmetric key:  create blob with unwrapped key.");
                        pbeWrappedData = createEncryptedContentInfo(ct, null, unwrappedSecData, null, pass);
                    } else {
                        pbeWrappedData = createEncryptedContentInfo(ct, symKey, null, null, pass);
                    }

                } else if (dataType.equals(KeyRequestResource.PASS_PHRASE_TYPE)) {

                    logger.debug("SecurityDataProcessor.recover(): encrypt stored passphrase with transport passphrase");
                    pbeWrappedData = createEncryptedContentInfo(ct, null, unwrappedSecData, null, pass);
                } else if (dataType.equals(KeyRequestResource.ASYMMETRIC_KEY_TYPE)) {
                    if (encrypted) {
                        logger.debug("SecurityDataProcessor.recover(): allowEncDecyypt_recovery: true, asymmetric key:  create blob with unwrapped key.");
                        pbeWrappedData = createEncryptedContentInfo(ct, null, unwrappedSecData, null, pass);
                    } else {
                        logger.debug("SecurityDataProcessor.recover(): wrap stored private key with transport passphrase");
                        pbeWrappedData = createEncryptedContentInfo(ct, null, null, privateKey,
                                pass);
                    }
                }

                params.put(Request.SECURITY_DATA_PASS_WRAPPED_DATA, pbeWrappedData);

            } catch (Exception e) {
                throw new EBaseException("Cannot unwrap passphrase: " + e, e);

            } finally {
                if (pass != null) {
                    pass.clear();
                }

                jssSubsystem.obscureBytes(unwrappedPass);

            }

        } else {
            logger.debug("SecurityDataProcessor.recover(): secure retrieved data with session key");

            if (dataType.equals(KeyRequestResource.SYMMETRIC_KEY_TYPE)) {
                logger.debug("SecurityDataProcessor.recover(): wrap or encrypt stored symmetric key with session key");
                try {
                    if (encrypted) {
                        logger.debug("SecurityDataProcessor.recover(): encrypt symmetric key with session key as per allowEncDecrypt_recovery: true.");
                        unwrappedSess = transportUnit.unwrap_session_key(ct, wrappedSessKey,
                                SymmetricKey.Usage.ENCRYPT, wrapParams);
                        key_data = CryptoUtil.encryptUsingSymmetricKey(
                                ct,
                                unwrappedSess,
                                unwrappedSecData,
                                wrapParams.getPayloadEncryptionAlgorithm(),
                                wrapParams.getPayloadEncryptionIV());
                    } else {
                        unwrappedSess = transportUnit.unwrap_session_key(ct, wrappedSessKey,
                                SymmetricKey.Usage.WRAP, wrapParams);
                        key_data = CryptoUtil.wrapUsingSymmetricKey(
                                ct,
                                unwrappedSess,
                                symKey,
                                wrapParams.getPayloadWrappingIV(),
                                wrapParams.getPayloadWrapAlgorithm());
                    }

                } catch (Exception e) {
                    jssSubsystem.obscureBytes(unwrappedSecData);
                    throw new EBaseException("Cannot wrap symmetric key: " + e, e);
                }

            } else if (dataType.equals(KeyRequestResource.PASS_PHRASE_TYPE)) {
                logger.debug("SecurityDataProcessor.recover(): encrypt stored passphrase with session key");
                try {
                    unwrappedSess = transportUnit.unwrap_session_key(ct, wrappedSessKey,
                            SymmetricKey.Usage.ENCRYPT, wrapParams);

                    key_data = CryptoUtil.encryptUsingSymmetricKey(
                            ct,
                            unwrappedSess,
                            unwrappedSecData,
                            wrapParams.getPayloadEncryptionAlgorithm(),
                            wrapParams.getPayloadEncryptionIV());
                } catch (Exception e) {
                    jssSubsystem.obscureBytes(unwrappedSecData);
                    throw new EBaseException("Cannot encrypt passphrase: " + e, e);
                }

            } else if (dataType.equals(KeyRequestResource.ASYMMETRIC_KEY_TYPE)) {
                logger.debug("SecurityDataProcessor.recover(): wrap or encrypt stored private key with session key");
                try {
                    if (encrypted) {
                        logger.debug("SecurityDataProcessor.recover(): encrypt symmetric key.");
                        unwrappedSess = transportUnit.unwrap_session_key(ct, wrappedSessKey,
                                SymmetricKey.Usage.ENCRYPT, wrapParams);

                        key_data = CryptoUtil.encryptUsingSymmetricKey(
                                ct,
                                unwrappedSess,
                                unwrappedSecData,
                                wrapParams.getPayloadEncryptionAlgorithm(),
                                wrapParams.getPayloadEncryptionIV());

                    } else {
                        unwrappedSess = transportUnit.unwrap_session_key(ct, wrappedSessKey,
                                SymmetricKey.Usage.WRAP, wrapParams);
                        key_data = CryptoUtil.wrapUsingSymmetricKey(
                                ct,
                                unwrappedSess,
                                privateKey,
                                wrapParams.getPayloadWrappingIV(),
                                wrapParams.getPayloadWrapAlgorithm());
                    }

                } catch (Exception e) {
                    jssSubsystem.obscureBytes(unwrappedSecData);
                    throw new EBaseException("Cannot wrap private key: " + e, e);
                }
            }

            String wrappedKeyData = Utils.base64encode(key_data, false);
            params.put(Request.SECURITY_DATA_SESS_WRAPPED_DATA, wrappedKeyData);
        }

        params.put(Request.SECURITY_DATA_PL_ENCRYPTION_OID,
                wrapParams.getPayloadEncryptionAlgorithmName());

        params.put(Request.SECURITY_DATA_PL_WRAPPING_NAME,
                wrapParams.getPayloadWrapAlgorithm().toString());

        if (encrypted || dataType.equals(KeyRequestResource.PASS_PHRASE_TYPE)) {
            params.put(Request.SECURITY_DATA_PL_WRAPPED, Boolean.toString(false));
            if (wrapParams.getPayloadEncryptionIV() != null) {
                params.put(Request.SECURITY_DATA_IV_STRING_OUT, ivStr);
            }
        } else {
            //secret has wrapped using a key wrapping algorithm
            params.put(Request.SECURITY_DATA_PL_WRAPPED, Boolean.toString(true));
            if (wrapParams.getPayloadWrappingIV() != null) {
                params.put(Request.SECURITY_DATA_IV_STRING_OUT, ivStr_wrap);
            }
        }


        //If we made it this far, all is good, and clear out the unwrappedSecData before returning.
        jssSubsystem.obscureBytes(unwrappedSecData);

        params.put(Request.SECURITY_DATA_TYPE, dataType);
        request.setExtData(Request.RESULT, Request.RES_SUCCESS);

        return false; //return true ? TODO
    }

    /***
     * This method returns an IV for the Encryption Algorithm referenced in OID.
     * If the oid is null, we return an IV for the default encryption algorithm.
     * The method checks to see if the encryption algorithm requires an IV by checking
     * the parameterClasses() for the encryption algorithm.
     *
     * @param oid           -- OID of encryption algorithm (as a string)
     * @param defaultAlg    -- default encryption algorithm
     * @return              -- initialization vector or null if none needed
     * @throws Exception if algorithm is not found, or if default and OID are null.
     *                   (ie. algorithm is unknown)
     */
    private byte[] generate_iv(String oid, EncryptionAlgorithm defaultAlg) throws Exception {

        EncryptionAlgorithm alg = oid != null? EncryptionAlgorithm.fromOID(new OBJECT_IDENTIFIER(oid)):
            defaultAlg;

        if (alg == null) {
            throw new EBaseException("Cannot determine encryption algorithm to generate IV");
        };

        if (alg.getParameterClasses() == null)
            return null;

        int numBytes = alg.getIVLength();
        byte[] bytes = new byte[numBytes];

        KRAEngine engine = KRAEngine.getInstance();
        JssSubsystem jssSubsystem = engine.getJSSSubsystem();

        SecureRandom random = jssSubsystem.getRandomNumberGenerator();
        random.nextBytes(bytes);

        return bytes;
    }

    /***
     * This method returns an IV for the KeyWrap algorithm referenced in wrapName.
     * If the wrapName is null, we return an IV for the default wrap algorithm.
     * The method checks to see if the key wrap algorithm requires an IV by checking
     * the parameterClasses() for the key wrap algorithm.
     *
     * @param wrapName      -- name of the key wrap algorithm (as defined in JSS)
     * @param defaultAlg    -- default wrapping parameters
     * @return              -- initialization vector or null if none needed
     * @throws Exception if algorithm is not found, or if default and OID are null.
     *                   (ie. algorithm is unknown)
     */
    private byte[] generate_wrap_iv(String wrapName, KeyWrapAlgorithm defaultAlg) throws Exception {

        KeyWrapAlgorithm alg = wrapName != null ? KeyWrapAlgorithm.fromString(wrapName) :
            defaultAlg;

        if (alg == null) {
            throw new EBaseException("Cannot determine keywrap algorithm to generate IV");
        }

        if (alg.getParameterClasses() == null)
            return null;

        int numBytes = alg.getBlockSize();
        byte[] bytes = new byte[numBytes];

        KRAEngine engine = KRAEngine.getInstance();
        JssSubsystem jssSubsystem = engine.getJSSSubsystem();

        SecureRandom random = jssSubsystem.getRandomNumberGenerator();
        random.nextBytes(bytes);

        return bytes;
    }

    public SymmetricKey recoverSymKey(KeyRecord keyRecord)
            throws EBaseException {

        try {
            SymmetricKey symKey =
                    storageUnit.unwrap(
                            keyRecord.getPrivateKeyData(),
                            KeyRequestService.SYMKEY_TYPES.get(keyRecord.getAlgorithm()),
                            keyRecord.getKeySize(),
                            keyRecord.getWrappingParams(storageUnit.getOldWrappingParams()));
            return symKey;
        } catch (Exception e) {
            throw new EKRAException(CMS.getUserMessage("CMS_KRA_RECOVERY_FAILED_1",
                    "recoverSymKey() " + e.toString()));
        }
    }

    public byte[] recoverSecurityData(KeyRecord keyRecord)
            throws EBaseException {
        try {
            return storageUnit.decryptInternalPrivate(
                    keyRecord.getPrivateKeyData(),
                    keyRecord.getWrappingParams(storageUnit.getOldWrappingParams()));
        } catch (Exception e) {
            logger.error("Failed to recover security data: " + e.getMessage(), e);
            throw new EKRAException(CMS.getUserMessage("CMS_KRA_RECOVERY_FAILED_1",
                    "recoverSecurityData() " + e.toString()));
        }
    }

    //ToDo: This might fit in JSS.
    private static EncryptedContentInfo
            createEncryptedContentInfoPBEOfKey(PBEAlgorithm keyGenAlg, Password password, byte[] salt,
                    int iterationCount,
                    KeyGenerator.CharToByteConverter charToByteConverter,
                    SymmetricKey symKey, PrivateKey privateKey, CryptoToken token)
                    throws Exception {

        if (keyGenAlg == null) {
            throw new NoSuchAlgorithmException("Key generation algorithm  is NULL");
        }
        PBEAlgorithm pbeAlg = keyGenAlg;

        KeyGenerator kg = token.getKeyGenerator(keyGenAlg);
        PBEKeyGenParams pbekgParams = new PBEKeyGenParams(
                password, salt, iterationCount);
        if (charToByteConverter != null) {
            kg.setCharToByteConverter(charToByteConverter);
        }
        kg.initialize(pbekgParams);
        SymmetricKey key = kg.generate();

        EncryptionAlgorithm encAlg = pbeAlg.getEncryptionAlg();
        AlgorithmParameterSpec params = null;
        if (encAlg.getParameterClass().equals(IVParameterSpec.class)) {
            params = new IVParameterSpec(kg.generatePBE_IV());
        } else if (encAlg.getParameterClass().equals(
                RC2ParameterSpec.class)) {
            params = new RC2ParameterSpec(key.getStrength(),
                                kg.generatePBE_IV());
        }

        byte[] encrypted = null;
        if (symKey != null) {
            encrypted = CryptoUtil.wrapUsingSymmetricKey(token, key, symKey, (IVParameterSpec) params,
                    KeyWrapAlgorithm.DES3_CBC_PAD);
        } else if (privateKey != null) {
            encrypted = CryptoUtil.wrapUsingSymmetricKey(token, key, privateKey, (IVParameterSpec) params,
                    KeyWrapAlgorithm.DES3_CBC_PAD);
        }
        if (encrypted == null) {
            //TODO - think about the exception to be thrown
        }
        PBEParameter pbeParam = new PBEParameter(salt, iterationCount);
        AlgorithmIdentifier encAlgID = new AlgorithmIdentifier(
                keyGenAlg.toOID(), pbeParam);

        EncryptedContentInfo encCI = new EncryptedContentInfo(
                ContentInfo.DATA,
                encAlgID,
                new OCTET_STRING(encrypted));

        return encCI;

    }

    private static String createEncryptedContentInfo(CryptoToken ct, SymmetricKey symKey, byte[] securityData, PrivateKey privateKey,
            Password password)
            throws EBaseException {

        EncryptedContentInfo cInfo = null;
        String retData = null;
        PBEAlgorithm keyGenAlg = PBEAlgorithm.PBE_SHA1_DES3_CBC;

        byte[] encoded = null;
        try {
            PasswordConverter passConverter = new
                    PasswordConverter();
            byte salt[] = { 0x01, 0x01, 0x01, 0x01 };
            if (symKey != null) {

                cInfo = createEncryptedContentInfoPBEOfKey(keyGenAlg, password, salt,
                        1,
                        passConverter,
                        symKey, null, ct);

            } else if (securityData != null) {

                cInfo = EncryptedContentInfo.createPBE(keyGenAlg, password, salt, 1, passConverter, securityData);
            } else if (privateKey != null) {
                cInfo = createEncryptedContentInfoPBEOfKey(keyGenAlg, password, salt,
                        1,
                        passConverter,
                        null, privateKey, ct);
            }

            if(cInfo == null) {
                throw new EBaseException("Can't create a PBE wrapped EncryptedContentInfo!");
            }

            ByteArrayOutputStream oStream = new ByteArrayOutputStream();
            cInfo.encode(oStream);
            encoded = oStream.toByteArray();
            retData = Utils.base64encode(encoded, false);

        } catch (Exception e) {
            throw new EBaseException("Can't create a PBE wrapped EncryptedContentInfo! " + e.toString());
        }

        return retData;
    }
}
