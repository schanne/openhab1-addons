/**
 * Copyright (c) 2010-2016 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.binding.km200.internal;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.httpclient.DefaultHttpMethodRetryHandler;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openhab.binding.km200.KM200BindingProvider;
import org.openhab.core.items.Item;
import org.openhab.core.library.items.DateTimeItem;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.items.StringItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteStreams;

/**
 * The KM200Comm class does the communication to the device and does any encryption/decryption/converting jobs
 *
 * @author Markus Eckhardt
 *
 * @since 1.9.0
 */

class KM200Comm {

    private static final Logger logger = LoggerFactory.getLogger(KM200Comm.class);
    private HttpClient client = null;
    private KM200Device device = null;

    public KM200Comm(KM200Device device) {
        this.device = device;
    }

    /**
     * This function removes zero padding from a byte array.
     *
     */
    public static byte[] removeZeroPadding(byte[] bytes) {
        int i = bytes.length - 1;
        while (i >= 0 && bytes[i] == 0) {
            --i;
        }
        return Arrays.copyOf(bytes, i + 1);
    }

    /**
     * This function adds zero padding to a byte array.
     *
     */
    public static byte[] addZeroPadding(byte[] bdata, int bSize, String cSet) throws UnsupportedEncodingException {
        int encrypt_padchar = bSize - (bdata.length % bSize);
        byte[] padchars = new String(new char[encrypt_padchar]).getBytes(cSet);
        byte[] padded_data = new byte[bdata.length + padchars.length];
        System.arraycopy(bdata, 0, padded_data, 0, bdata.length);
        System.arraycopy(padchars, 0, padded_data, bdata.length, padchars.length);
        return padded_data;
    }

    /**
     * This function converts a hex string to a byte array
     *
     */
    public static byte[] hexToBytes(String str) {
        if (str == null) {
            return null;
        } else if (str.length() < 2) {
            return null;
        } else {
            int len = str.length() / 2;
            byte[] buffer = new byte[len];
            for (int i = 0; i < len; i++) {
                buffer[i] = (byte) Integer.parseInt(str.substring(i * 2, i * 2 + 2), 16);
            }
            return buffer;
        }
    }

    /**
     * This function converts a byte array to a hex string
     *
     */
    public static String bytesToHex(byte[] data) {
        if (data == null) {
            return null;
        }

        int len = data.length;
        String str = "";
        for (int i = 0; i < len; i++) {
            if ((data[i] & 0xFF) < 16) {
                str = str + "0" + Integer.toHexString(data[i] & 0xFF);
            } else {
                str = str + Integer.toHexString(data[i] & 0xFF);
            }
        }
        return str;
    }

    /**
     * This function does the GET http communication to the device
     *
     */
    public byte[] getDataFromService(String service) {
        byte[] responseBodyB64 = null;
        // Create an instance of HttpClient.
        if (client == null) {
            client = new HttpClient();
        }
        synchronized (client) {

            // Create a method instance.
            GetMethod method = new GetMethod("http://" + device.getIP4Address() + service);

            // Provide custom retry handler is necessary
            method.getParams().setParameter(HttpMethodParams.RETRY_HANDLER,
                    new DefaultHttpMethodRetryHandler(3, false));
            // Set the right header
            method.setRequestHeader("Accept", "application/json");
            method.addRequestHeader("User-Agent", "TeleHeater/2.2.3");

            try {
                // Execute the method.
                int statusCode = client.executeMethod(method);
                // Check the status and the forbidden 403 Error.
                if (statusCode != HttpStatus.SC_OK) {
                    String statusLine = method.getStatusLine().toString();
                    if (statusLine.contains(" 403 ")) {
                        byte[] test = new byte[1];
                        return test;
                    } else {
                        logger.error("HTTP GET failed: {}", method.getStatusLine());
                        return null;
                    }
                }
                device.setCharSet(method.getResponseCharSet());
                // Read the response body.
                responseBodyB64 = ByteStreams.toByteArray(method.getResponseBodyAsStream());

            } catch (HttpException e) {
                logger.error("Fatal protocol violation: {}", e);
                e.printStackTrace();
            } catch (IOException e) {
                logger.error("Fatal transport error: {}", e);
                e.printStackTrace();
            } finally {
                // Release the connection.
                method.releaseConnection();
            }
            return responseBodyB64;
        }
    }

    /**
     * This function does the SEND http communication to the device
     *
     */
    public Integer sendDataToService(String service, byte[] data) {
        // Create an instance of HttpClient.
        Integer rCode = null;
        if (client == null) {
            client = new HttpClient();
        }
        synchronized (client) {

            // Create a method instance.
            PostMethod method = new PostMethod("http://" + device.getIP4Address() + service);

            // Provide custom retry handler is necessary
            method.getParams().setParameter(HttpMethodParams.RETRY_HANDLER,
                    new DefaultHttpMethodRetryHandler(3, false));
            // Set the right header
            method.setRequestHeader("Accept", "application/json");
            method.addRequestHeader("User-Agent", "TeleHeater/2.2.3");
            method.setRequestEntity(new ByteArrayRequestEntity(data));

            try {
                rCode = client.executeMethod(method);

            } catch (Exception e) {
                logger.error("Failed to send data {}", e);

            } finally {
                // Release the connection.
                method.releaseConnection();
            }
            return rCode;
        }
    }

    /**
     * This function does the decoding for a new message from the device
     *
     */
    public String decodeMessage(byte[] encoded) {
        String retString = null;
        byte[] decodedB64 = null;

        try {
            decodedB64 = Base64.decodeBase64(encoded);
        } catch (Exception e) {
            logger.error("Message is not in valid Base64 scheme: {}", e);
            e.printStackTrace();
            return null;
        }
        try {
            /* Check whether the length of the decryptData is NOT multiplies of 16 */
            if ((decodedB64.length & 0xF) != 0) {
                /* Return the data */
                retString = new String(decodedB64, device.getCharSet());
                return retString;
            }
            // --- create cipher
            final Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(device.getCryptKeyPriv(), "AES"));
            final byte[] decryptedData = cipher.doFinal(decodedB64);
            byte[] decryptedDataWOZP = removeZeroPadding(decryptedData);
            retString = new String(decryptedDataWOZP, device.getCharSet());
            return retString;
        } catch (UnsupportedEncodingException | GeneralSecurityException e) {
            // failure to authenticate
            logger.error("Exception on encoding: {}", e);
            return null;
        }
    }

    /**
     * This function does the encoding for a new message to the device
     *
     */
    public byte[] encodeMessage(String data) {
        byte[] encryptedDataB64 = null;

        try {
            // --- create cipher
            byte[] bdata = data.getBytes(device.getCharSet());
            final Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(device.getCryptKeyPriv(), "AES"));
            logger.debug("Create padding..");
            int bsize = cipher.getBlockSize();
            logger.debug("Add Padding and Encrypt AES..");
            final byte[] encryptedData = cipher.doFinal(addZeroPadding(bdata, bsize, device.getCharSet()));
            logger.debug("Encrypt B64..");
            try {
                encryptedDataB64 = Base64.encodeBase64(encryptedData);
            } catch (Exception e) {
                logger.error("Base64encoding not possible: {}", e);
                e.printStackTrace();
            }
            return encryptedDataB64;
        } catch (UnsupportedEncodingException | GeneralSecurityException e) {
            // failure to authenticate
            logger.error("Exception on encoding: {}", e);
            return null;
        }
    }

    /**
     * This function checks the capabilities of a service on the device
     *
     */
    public void initObjects(String service) {
        String id = null, type = null, decodedData = null;
        Integer writeable = 0;
        Integer recordable = 0;
        JSONObject nodeRoot = null;
        KM200CommObject newObject = null;
        logger.debug("Init: {}", service);
        if (device.blacklistMap.contains(service)) {
            logger.debug("Service on blacklist: {}", service);
            return;
        }
        byte[] recData = getDataFromService(service.toString());
        try {
            if (recData == null) {
                throw new RuntimeException("Communication is not possible!");
            }
            if (recData.length == 0) {
                throw new RuntimeException("No reply from KM200!");
            }
            /* Look whether the communication was forbidden */
            if (recData.length == 1) {
                newObject = new KM200CommObject(service, "", 0, 0, 0);
                device.serviceMap.put(service, newObject);
                return;
            }
            decodedData = decodeMessage(recData);
            if (decodedData == null) {
                throw new RuntimeException("Decoding of the KM200 message is not possible!");
            }
            if (decodedData.length() > 0) {
                nodeRoot = new JSONObject(decodedData);
                type = nodeRoot.getString("type");
                id = nodeRoot.getString("id");
            } else {
                logger.error("Get empty reply");
                return;
            }

            /* Check the service features and set the flags */
            if (nodeRoot.has("writeable")) {
                Integer val = nodeRoot.getInt("writeable");
                logger.debug(val.toString());
                writeable = val;
            }
            if (nodeRoot.has("recordable")) {
                Integer val = nodeRoot.getInt("recordable");
                logger.debug(val.toString());
                recordable = val;
            }
            logger.debug("Typ: {}", type);

            newObject = new KM200CommObject(id, type, writeable, recordable);
            newObject.setJSONData(decodedData.toString());

            /* Check whether the type is a single value containing a string value */
            if (type.equals("stringValue")) {
                Object valObject = null;
                logger.debug("initDevice: type string value: {}", decodedData.toString());
                valObject = new String(nodeRoot.getString("value"));
                newObject.setValue(valObject);
                if (nodeRoot.has("allowedValues")) {
                    List<String> valParas = new ArrayList<String>();
                    JSONArray paras = nodeRoot.getJSONArray("allowedValues");
                    for (int i = 0; i < paras.length(); i++) {
                        String subJSON = (String) paras.get(i);
                        valParas.add(subJSON);
                    }
                    newObject.setValueParameter(valParas);
                }
                device.serviceMap.put(id, newObject);

            } else if (type
                    .equals("floatValue")) { /* Check whether the type is a single value containing a float value */
                Object valObject = null;
                logger.debug("initDevice: type float value: {}", decodedData.toString());
                valObject = new Float(nodeRoot.getDouble("value"));
                newObject.setValue(valObject);
                if (nodeRoot.has("minValue") && nodeRoot.has("maxValue")) {
                    List<Float> valParas = new ArrayList<Float>();
                    valParas.add(new Float(nodeRoot.getDouble("minValue")));
                    valParas.add(new Float(nodeRoot.getDouble("maxValue")));
                    newObject.setValueParameter(valParas);
                }
                device.serviceMap.put(id, newObject);

            } else if (type.equals("switchProgram")) { /* Check whether the type is a switchProgram */
                logger.debug("initDevice: type switchProgram {}", decodedData.toString());
                KM200SwitchProgramService sPService = new KM200SwitchProgramService();
                sPService.setMaxNbOfSwitchPoints(nodeRoot.getInt("maxNbOfSwitchPoints"));
                sPService.setMaxNbOfSwitchPointsPerDay(nodeRoot.getInt("maxNbOfSwitchPointsPerDay"));
                sPService.setSwitchPointTimeRaster(nodeRoot.getInt("switchPointTimeRaster"));
                JSONObject propObject = nodeRoot.getJSONObject("setpointProperty");
                sPService.setSetpointProperty(propObject.getString("id"));
                sPService.updateSwitches(nodeRoot);
                newObject.setValueParameter(sPService);
                newObject.setJSONData(decodedData.toString());
                device.serviceMap.put(id, newObject);
                device.virtualList.add(newObject);

            } else if (type.equals("errorList")) { /* Check whether the type is a errorList */
                logger.debug("initDevice: type errorList: {}", decodedData.toString());
                JSONArray errorValues = nodeRoot.getJSONArray("values");
                newObject.setValue(errorValues);
                /* have to be completed */

            } else if (type.equals("refEnum")) { /* Check whether the type is a refEnum */
                logger.debug("initDevice: type refEnum: {}", decodedData.toString());
                device.serviceMap.put(id, newObject);
                JSONArray refers = nodeRoot.getJSONArray("references");
                for (int i = 0; i < refers.length(); i++) {
                    JSONObject subJSON = refers.getJSONObject(i);
                    id = subJSON.getString("id");
                    initObjects(id);
                }

            } else if (type.equals("moduleList")) { /* Check whether the type is a moduleList */
                logger.debug("initDevice: type moduleList: {}", decodedData.toString());
                device.serviceMap.put(id, newObject);
                JSONArray vals = nodeRoot.getJSONArray("values");
                for (int i = 0; i < vals.length(); i++) {
                    JSONObject subJSON = vals.getJSONObject(i);
                    id = subJSON.getString("id");
                    initObjects(id);
                }

            } else if (type.equals("yRecording")) { /* Check whether the type is a yRecording */
                logger.debug("initDevice: type yRecording: {}", decodedData.toString());
                device.serviceMap.put(id, newObject);
                /* have to be completed */

            } else if (type.equals("systeminfo")) { /* Check whether the type is a systeminfo */
                logger.debug("initDevice: type systeminfo: {}", decodedData.toString());
                JSONArray sInfo = nodeRoot.getJSONArray("values");
                newObject.setValue(sInfo);
                device.serviceMap.put(id, newObject);
                /* have to be completed */

            } else { /* Unknown type */
                logger.info("initDevice: type unknown for service: {}",
                        service.toString() + "Data:" + decodedData.toString());
                device.serviceMap.put(id, newObject);
            }
        } catch (

        JSONException e) {
            logger.error("Parsingexception in JSON: {} data: {}", e, decodedData);
            e.printStackTrace();
        }
    }

    /**
     * This function creates the virtual services
     *
     */
    public void initVirtualObjects() {
        KM200CommObject newObject = null;
        for (KM200CommObject object : device.virtualList) {
            logger.debug(object.getFullServiceName());
            String id = object.getFullServiceName();
            String type = object.getServiceType();
            switch (type) {
                case "switchProgram":
                    KM200SwitchProgramService sPService = ((KM200SwitchProgramService) object.getValueParameter());
                    sPService.determineSwitchNames(device);
                    newObject = new KM200CommObject(id + "/weekday", type, object.getWriteable(),
                            object.getRecordable(), 1, id);
                    device.serviceMap.put(id + "/weekday", newObject);
                    newObject = new KM200CommObject(id + "/nbrCycles", type, object.getWriteable(),
                            object.getRecordable(), 1, id);
                    device.serviceMap.put(id + "/nbrCycles", newObject);
                    newObject = new KM200CommObject(id + "/cycle", type, object.getWriteable(), object.getRecordable(),
                            1, id);
                    device.serviceMap.put(id + "/cycle", newObject);
                    logger.debug("On: {}  Of: {}", id + "/" + sPService.getPositiveSwitch(),
                            id + "/" + sPService.getNegativeSwitch());
                    newObject = new KM200CommObject(id + "/" + sPService.getPositiveSwitch(), type,
                            object.getWriteable(), object.getRecordable(), 1, id);
                    device.serviceMap.put(id + "/" + sPService.getPositiveSwitch(), newObject);
                    newObject = new KM200CommObject(id + "/" + sPService.getNegativeSwitch(), type,
                            object.getWriteable(), object.getRecordable(), 1, id);
                    device.serviceMap.put(id + "/" + sPService.getNegativeSwitch(), newObject);

                    break;
            }
        }
    }

    /**
     * This function checks the state of a service on the device
     *
     */
    public State getProvidersState(KM200BindingProvider provider, String item) {
        synchronized (device) {
            String decodedData = null;
            String type = null;
            byte[] recData = null;
            KM200CommObject object = null;
            String service = provider.getService(item);
            Class<? extends Item> itemType = provider.getItemType(item);
            logger.debug("Check state of: {} type: {} item: {}", service, type, itemType.getName().toString());
            if (device.blacklistMap.contains(service)) {
                logger.debug("Service on blacklist: {}", service);
                return null;
            }
            if (device.serviceMap.containsKey(service)) {
                object = device.serviceMap.get(service);
                if (object.getReadable() == 0) {
                    logger.error("Service is listed as protected (reading is not possible): {}", service);
                    return null;
                }
                type = object.getServiceType();
            } else {
                logger.error("Service is not in the determined device service list: {}", service);
                return null;
            }
            /* For using of virtual services only one receive on the parent service is needed */
            if (!object.getUpdated()
                    || (object.getVirtual() == 1 && !device.serviceMap.get(object.getParent()).getUpdated())) {

                if (object.getVirtual() == 1) {
                    /* If it's a virtual service then receive the data from parent service */
                    recData = getDataFromService(object.getParent());
                } else {
                    recData = getDataFromService(service);
                }

                if (recData == null) {
                    throw new RuntimeException("Communication is not possible!");
                }
                if (recData.length == 0) {
                    throw new RuntimeException("No reply from KM200!");
                }
                /* Look whether the communication was forbidden */
                if (recData.length == 1) {
                    logger.error("Service is listed as readable but communication is forbidden: {}", service);
                    return null;
                }
                decodedData = decodeMessage(recData);
                logger.debug("Check state of data:" + decodedData);
                if (decodedData == null) {
                    throw new RuntimeException("Decoding of the KM200 message is not possible!");
                }
                if (object.getVirtual() == 1) {
                    device.serviceMap.get(object.getParent()).setJSONData(decodedData.toString());
                    device.serviceMap.get(object.getParent()).setUpdated(true);
                } else {
                    object.setJSONData(decodedData.toString());
                }
                object.setUpdated(true);

            } else {
                /* If already updated then use the saved data */
                if (object.getVirtual() == 1) {
                    decodedData = device.serviceMap.get(object.getParent()).getJSONData();
                } else {
                    decodedData = object.getJSONData();
                }
            }
            /* Data is received, now parsing it */
            return parseJSONData(decodedData, type, item, provider);
        }
    }

    /**
     * This function parses the receviced JSON Data and return the right state
     *
     */
    public State parseJSONData(String decodedData, String type, String item, KM200BindingProvider provider) {
        JSONObject nodeRoot = null;
        State state = null;
        Class<? extends Item> itemType = provider.getItemType(item);
        String service = provider.getService(item);
        KM200CommObject object = device.serviceMap.get(service);
        logger.debug("parseJSONData service: {}, data: {}", service, decodedData);
        /* Now parsing of the JSON String depending on its type and the type of binding item */
        try {
            if (decodedData.length() > 0) {
                nodeRoot = new JSONObject(decodedData);
            } else {
                logger.error("Get empty reply");
                return null;
            }
            /* Check whether the type is a single value containing a string value */
            if (type.equals("stringValue")) {
                logger.debug("initDevice: type string value: {}", decodedData.toString());
                String val = nodeRoot.getString("value");
                /* SwitchItem Binding */
                if (itemType.isAssignableFrom(SwitchItem.class)) {
                    if (provider.getParameter(item).containsKey("on")) {
                        if (val.equals(provider.getParameter(item).get("off"))) {
                            state = OnOffType.OFF;
                        } else if (val.equals(provider.getParameter(item).get("on"))) {
                            state = OnOffType.ON;
                        }
                    } else {
                        logger.error("Switch-Item only on configured on/off string values: {}", decodedData.toString());
                        return null;
                    }

                    /* NumberItem Binding */
                } else if (itemType.isAssignableFrom(NumberItem.class)) {
                    try {
                        state = new DecimalType(Float.parseFloat(val));
                    } catch (NumberFormatException e) {
                        logger.error("Conversion of the string value to Decimal wasn't possible, data: {} error: {}",
                                decodedData.toString(), e);
                        return null;
                    }
                    /* DateTimeItem Binding */
                } else if (itemType.isAssignableFrom(DateTimeItem.class)) {
                    try {
                        state = new DateTimeType(val);
                    } catch (IllegalArgumentException e) {
                        logger.error("Conversion of the string value to DateTime wasn't possible, data: {} error: {}",
                                decodedData.toString(), e);
                        return null;
                    }

                    /* StringItem Binding */
                } else if (itemType.isAssignableFrom(StringItem.class)) {

                    state = new StringType(val);

                } else {
                    logger.error("Bindingtype not supported for string values: {}", itemType.getClass().toString());
                    return null;
                }

                return state;

            } else if (type
                    .equals("floatValue")) { /* Check whether the type is a single value containing a float value */
                logger.debug("state of type float value: {}", decodedData.toString());
                Float val = new Float(nodeRoot.getDouble("value"));
                /* NumberItem Binding */
                if (itemType.isAssignableFrom(NumberItem.class)) {
                    state = new DecimalType(val.floatValue());

                    /* StringItem Binding */
                } else if (itemType.isAssignableFrom(StringItem.class)) {
                    state = new StringType(val.toString());
                } else {
                    logger.error("Bindingtype not supported for float values: {}", itemType.getClass().toString());
                    return null;
                }
                return state;

            } else if (type.equals("switchProgram")) { /* Check whether the type is a switchProgram */
                KM200SwitchProgramService sPService = null;
                logger.debug("state of type switchProgram: {}", decodedData.toString());
                /* Get the KM200SwitchProgramService class object with all specific parameters */
                if (object.getVirtual() == 0) {
                    sPService = ((KM200SwitchProgramService) object.getValueParameter());
                } else {
                    sPService = ((KM200SwitchProgramService) device.serviceMap.get(object.getParent())
                            .getValueParameter());
                }
                /* Update the switches insode the KM200SwitchProgramService */
                sPService.updateSwitches(nodeRoot);

                /* the parsing of switch program-services have to be outside, using json in strings */
                if (object.getVirtual() == 1) {
                    return this.getVirtualState(object, itemType, service);
                } else {
                    /* if access to the parent non virtual service the return the switchPoints jsonarray */
                    if (itemType.isAssignableFrom(StringItem.class)) {
                        state = new StringType(nodeRoot.getJSONArray("switchPoints").toString());
                    } else {
                        logger.error(
                                "Bindingtype not supported for switchProgram, only json over strings supported: {}",
                                itemType.getClass().toString());
                        return null;
                    }
                    return state;
                }

            } else if (type.equals("errorList")) { /* Check whether the type is a errorList */
                logger.info("state of: type errorList is not supported yet: {}", decodedData.toString());
                /* have to be completed */

            } else if (type.equals("yRecording")) { /* Check whether the type is a yRecording */
                logger.info("state of: type yRecording is not supported yet: {}", decodedData.toString());
                /* have to be completed */

            } else if (type.equals("systeminfo")) { /* Check whether the type is a systeminfo */
                logger.info("state of: type systeminfo is not supported yet: {}", decodedData.toString());
                /* have to be completed */
            }

        } catch (JSONException e) {
            logger.error("Parsingexception in JSON, data: {} error: {} ", decodedData, e);
            e.printStackTrace();
        }
        return null;

    }

    /**
     * This function checks the virtual state of a service
     *
     */
    public State getVirtualState(KM200CommObject object, Class<? extends Item> itemType, String service) {
        State state = null;
        String type = object.getServiceType();
        logger.debug("Check virtual state of: {} type: {} item: {}", service, type, itemType.getName().toString());

        switch (type) {
            case "switchProgram":
                KM200SwitchProgramService sPService = ((KM200SwitchProgramService) device.serviceMap
                        .get(object.getParent()).getValueParameter());
                String[] servicePath = service.split("/");
                String virtService = servicePath[servicePath.length - 1];
                if (virtService.equals("weekday")) {
                    if (itemType.isAssignableFrom(StringItem.class)) {
                        String val = sPService.getActiveDay();
                        if (val == null) {
                            return null;
                        }
                        state = new StringType(val);
                    } else {
                        logger.error("Bindingtype not supported for day service: {}", itemType.getClass().toString());
                        return null;
                    }
                } else if (virtService.equals("nbrCycles")) {
                    if (itemType.isAssignableFrom(NumberItem.class)) {
                        Integer val = sPService.getNbrCycles();
                        if (val == null) {
                            return null;
                        }
                        state = new DecimalType(val);
                    } else {
                        logger.error("Bindingtype not supported for nbrCycles service: {}",
                                itemType.getClass().toString());
                        return null;
                    }

                } else if (virtService.equals("cycle")) {
                    if (itemType.isAssignableFrom(NumberItem.class)) {
                        Integer val = sPService.getActiveCycle();
                        if (val == null) {
                            return null;
                        }
                        state = new DecimalType(val);
                    } else {
                        logger.error("Bindingtype not supported for cycle service: {}", itemType.getClass().toString());
                        return null;
                    }
                } else if (virtService.equals(sPService.getPositiveSwitch())) {
                    if (itemType.isAssignableFrom(NumberItem.class)) {
                        Integer val = sPService.getActivePositiveSwitch();
                        if (val == null) {
                            return null;
                        }
                        state = new DecimalType(val);
                    } else if (itemType.isAssignableFrom(DateTimeItem.class)) {
                        Integer val = sPService.getActivePositiveSwitch();
                        if (val == null) {
                            return null;
                        }
                        Calendar rightNow = Calendar.getInstance();
                        Integer hour = val % 60;
                        Integer minute = val - (hour * 60);
                        rightNow.set(Calendar.HOUR_OF_DAY, hour);
                        rightNow.set(Calendar.MINUTE, minute);
                        state = new DateTimeType(rightNow);
                    } else {
                        logger.error("Bindingtype not supported for cycle service: {}", itemType.getClass().toString());
                        return null;
                    }

                } else if (virtService.equals(sPService.getNegativeSwitch())) {
                    if (itemType.isAssignableFrom(NumberItem.class)) {
                        Integer val = sPService.getActiveNegativeSwitch();
                        if (val == null) {
                            return null;
                        }
                        state = new DecimalType(val);
                    } else if (itemType.isAssignableFrom(DateTimeItem.class)) {
                        Integer val = sPService.getActiveNegativeSwitch();
                        if (val == null) {
                            return null;
                        }
                        Calendar rightNow = Calendar.getInstance();
                        Integer hour = val % 60;
                        Integer minute = val - (hour * 60);
                        rightNow.set(Calendar.HOUR_OF_DAY, hour);
                        rightNow.set(Calendar.MINUTE, minute);
                        state = new DateTimeType(rightNow);
                    } else {
                        logger.error("Bindingtype not supported for cycle service: {}", itemType.getClass().toString());
                        return null;
                    }
                }
                break;
        }
        return state;

    }

    /**
     * This function sets the state of a service on the device
     *
     */
    public byte[] sendProvidersState(KM200BindingProvider provider, String item, Command command) {
        synchronized (device) {
            String service = provider.getService(item);
            String type = null;
            String dataToSend = null;
            KM200CommObject object = null;
            Class<? extends Item> itemType = provider.getItemType(item);

            logger.debug("Prepare item for send: {} type: {} item: {}", service, type, itemType.getName().toString());
            if (device.blacklistMap.contains(service)) {
                logger.debug("Service on blacklist: {}", service);
                return null;
            }
            if (device.serviceMap.containsKey(service)) {
                if (device.serviceMap.get(service).getWriteable() == 0) {
                    logger.error("Service is listed as read-only: {}", service);
                    return null;
                }
                object = device.serviceMap.get(service);
                type = object.getServiceType();
            } else {
                logger.error("Service is not in the determined device service list: {}", service);
                return null;
            }
            /* The service is availible, set now the values depeding on the item and binding type */
            logger.debug("state of: {} type: {}", command.toString(), type.toString());
            /* Binding is a NumberItem */
            if (itemType.isAssignableFrom(NumberItem.class)) {
                Float val = ((DecimalType) command).floatValue();
                /* Check the capabilities of this service */
                if (object.getValueParameter() != null) {
                    @SuppressWarnings("unchecked")
                    List<Float> valParas = (List<Float>) object.getValueParameter();
                    Float minVal = valParas.get(0);
                    Float maxVal = valParas.get(1);
                    if (val < minVal) {
                        val = minVal;
                    }
                    if (val > maxVal) {
                        val = maxVal;
                    }
                }
                if (type.equals("floatValue")) {
                    dataToSend = new JSONObject().put("value", val).toString();
                } else if (type.equals("stringValue")) {
                    dataToSend = new JSONObject().put("value", val.toString()).toString();
                } else if (type.equals("switchProgram") && object.getVirtual() == 1) {
                    /* A switchProgram as NumberItem is always virtual */
                    dataToSend = sendVirtualState(object, itemType, service, command);
                } else {
                    logger.error("Not supported type for numberItem: {}", type.toString());
                }
                /* Binding is a StringItem */
            } else if (itemType.isAssignableFrom(StringItem.class)) {
                String val = ((StringType) command).toString();
                /* Check the capabilities of this service */
                if (object.getValueParameter() != null) {
                    @SuppressWarnings("unchecked")
                    List<String> valParas = (List<String>) object.getValueParameter();
                    if (!valParas.contains(val)) {
                        throw new IllegalArgumentException("Parameter is not in the service parameterlist:" + val);
                    }
                }
                if (type.equals("stringValue")) {
                    dataToSend = new JSONObject().put("value", val).toString();
                } else if (type.equals("floatValue")) {
                    dataToSend = new JSONObject().put("value", Float.parseFloat(val)).toString();
                } else if (type.equals("switchProgram")) {
                    if (object.getVirtual() == 1) {
                        dataToSend = sendVirtualState(object, itemType, service, command);
                    } else {
                        /* The JSONArray of switch items can be sended directly */
                        try {
                            /* Check whether ths input string is a valid JSONArray */
                            JSONArray userArray = new JSONArray(val);
                            dataToSend = userArray.toString();
                        } catch (Exception e) {
                            logger.error("The input for the switchProgram is not a valid JSONArray : {}", e);
                            return null;
                        }
                    }
                } else {
                    logger.error("Not supported type for stringItem: {}", type.toString());
                }

                /* Binding is a DateTimeItem */
            } else if (itemType.isAssignableFrom(DateTimeItem.class)) {
                String val = ((DateTimeType) command).toString();
                if (type.equals("stringValue")) {
                    dataToSend = new JSONObject().put("value", val).toString();
                } else if (type.equals("switchProgram")) {
                    dataToSend = sendVirtualState(object, itemType, service, command);
                } else {
                    logger.error("Not supported type for dateTimeItem: {}", type.toString());
                }

                /* Binding is a SwitchItem */
            } else if (itemType.isAssignableFrom(SwitchItem.class)) {
                String val = null;
                if (provider.getParameter(item).containsKey("on")) {
                    if (command == OnOffType.OFF) {
                        val = provider.getParameter(item).get("off");
                    } else if (command == OnOffType.ON) {
                        val = provider.getParameter(item).get("on");
                    }
                } else {
                    logger.error("witch-Item only on configured on/off string values {}", command.toString());
                    return null;
                }
                if (type.equals("stringValue")) {
                    dataToSend = new JSONObject().put("value", val).toString();
                } else {
                    logger.error("Not supported type for SwitchItem:{}", type.toString());
                }

            } else {
                logger.error("Bindingtype not supported: {}", itemType.getClass().toString());
                return null;
            }
            /* If some data is availible then we have to send it to device */
            if (dataToSend != null) {
                /* base64 + encoding */
                logger.debug("Encoding: {}", dataToSend);
                byte[] encData = encodeMessage(dataToSend);
                if (encData == null) {
                    logger.error("Couldn't encrypt data");
                    return null;
                }
                return encData;
            } else {
                return null;
            }
        }
    }

    /**
     * This function sets the state of a virtual service
     *
     */
    public String sendVirtualState(KM200CommObject object, Class<? extends Item> itemType, String service,
            Command command) {
        String dataToSend = null;
        String type = null;
        logger.debug("Check virtual state of: {} type: {} item: {}", service, type, itemType.getName().toString());
        object = device.serviceMap.get(service);
        KM200CommObject parObject = device.serviceMap.get(object.getParent());
        type = object.getServiceType();
        /* Binding is a StringItem */
        if (itemType.isAssignableFrom(StringItem.class)) {
            String val = ((StringType) command).toString();
            switch (type) {
                case "switchProgram":
                    KM200SwitchProgramService sPService = ((KM200SwitchProgramService) parObject.getValueParameter());
                    String[] servicePath = service.split("/");
                    String virtService = servicePath[servicePath.length - 1];
                    if (virtService.equals("weekday")) {
                        /* Only parameter changing without communication to device */
                        sPService.setActiveDay(val);
                    }
                    break;
            }
            /* Binding is a NumberItem */
        } else if (itemType.isAssignableFrom(NumberItem.class)) {
            Integer val = ((DecimalType) command).intValue();
            switch (type) {
                case "switchProgram":
                    KM200SwitchProgramService sPService = ((KM200SwitchProgramService) parObject.getValueParameter());
                    String[] servicePath = service.split("/");
                    String virtService = servicePath[servicePath.length - 1];
                    if (virtService.equals("nbrCycles")) {
                        /* ReadOnly */
                    } else if (virtService.equals("cycle")) {
                        /* Only parameter changing without communication to device */
                        sPService.setActiveCycle(val);
                    } else if (virtService.equals(sPService.getPositiveSwitch())) {
                        sPService.setActivePositiveSwitch(val);
                        /* Create a JSON Array from current switch configuration */
                        dataToSend = sPService.getUpdatedJSONData(parObject);
                    } else if (virtService.equals(sPService.getNegativeSwitch())) {
                        sPService.setActiveNegativeSwitch(val);
                        /* Create a JSON Array from current switch configuration */
                        dataToSend = sPService.getUpdatedJSONData(parObject);
                    }
                    break;
            }
        } else if (itemType.isAssignableFrom(DateTimeItem.class)) {
            Calendar cal = ((DateTimeType) command).getCalendar();
            KM200SwitchProgramService sPService = ((KM200SwitchProgramService) parObject.getValueParameter());
            String[] servicePath = service.split("/");
            String virtService = servicePath[servicePath.length - 1];
            if (virtService.equals(sPService.getPositiveSwitch())) {
                Integer minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
                minutes = (minutes % sPService.getSwitchPointTimeRaster()) * sPService.getSwitchPointTimeRaster();
                sPService.setActivePositiveSwitch(minutes);
                /* Create a JSON Array from current switch configuration */
                dataToSend = sPService.getUpdatedJSONData(parObject);
            } else if (virtService.equals(sPService.getNegativeSwitch())) {
                Integer minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
                minutes = (minutes % sPService.getSwitchPointTimeRaster()) * sPService.getSwitchPointTimeRaster();
                sPService.setActiveNegativeSwitch(minutes);
                /* Create a JSON Array from current switch configuration */
                dataToSend = sPService.getUpdatedJSONData(parObject);
            }
        }
        return dataToSend;
    }
}
