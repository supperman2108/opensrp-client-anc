package org.smartregister.anc.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Log;
import android.util.Pair;

import com.google.common.reflect.TypeToken;
import com.vijay.jsonwizard.activities.JsonFormActivity;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.smartregister.anc.BuildConfig;
import org.smartregister.anc.application.AncApplication;
import org.smartregister.anc.domain.FormLocation;
import org.smartregister.anc.helper.ECSyncHelper;
import org.smartregister.anc.helper.LocationHelper;
import org.smartregister.anc.view.LocationPickerView;
import org.smartregister.clientandeventmodel.Client;
import org.smartregister.clientandeventmodel.Event;
import org.smartregister.domain.Photo;
import org.smartregister.domain.ProfileImage;
import org.smartregister.domain.tag.FormTag;
import org.smartregister.repository.ImageRepository;
import org.smartregister.util.AssetHandler;
import org.smartregister.util.FormUtils;
import org.smartregister.view.activity.DrishtiApplication;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Created by keyman on 27/06/2018.
 */
public class JsonFormUtils extends org.smartregister.util.JsonFormUtils {
    private static final String TAG = JsonFormUtils.class.getCanonicalName();

    private static final String METADATA = "metadata";
    public static final String ENCOUNTER_TYPE = "encounter_type";
    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd-MM-yyyy");

    public static final String CURRENT_OPENSRP_ID = "current_opensrp_id";
    public static final String READ_ONLY = "read_only";

    public static JSONObject getFormAsJson(JSONObject form,
                                           String formName, String id,
                                           String currentLocationId) throws Exception {
        if (form == null) {
            return null;
        }

        String entityId = id;
        form.getJSONObject(METADATA).put(ENCOUNTER_LOCATION, currentLocationId);

        if (Constants.JSON_FORM.ANC_REGISTER.equals(formName)) {
            if (StringUtils.isNotBlank(entityId)) {
                entityId = entityId.replace("-", "");
            }

            // Inject opensrp id into the form
            JSONObject stepOne = form.getJSONObject(JsonFormUtils.STEP1);
            JSONArray jsonArray = stepOne.getJSONArray(JsonFormUtils.FIELDS);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                if (jsonObject.getString(JsonFormUtils.KEY)
                        .equalsIgnoreCase(DBConstants.KEY.ANC_ID)) {
                    jsonObject.remove(JsonFormUtils.VALUE);
                    jsonObject.put(JsonFormUtils.VALUE, entityId);
                }
            }
        } else if (Constants.JSON_FORM.ANC_CLOSE.equals(formName)) {
            if (StringUtils.isNotBlank(entityId)) {
                // Inject entity id into the remove form
                form.remove(JsonFormUtils.ENTITY_ID);
                form.put(JsonFormUtils.ENTITY_ID, entityId);
            }
        } else {
            Log.w(TAG, "Unsupported form requested for launch " + formName);
        }
        Log.d(TAG, "form is " + form.toString());
        return form;
    }

    public static Pair<Client, Event> processRegistrationForm(String jsonString, String providerId) {

        try {
            JSONObject jsonForm = toJSONObject(jsonString);
            if (jsonForm == null) {
                return null;
            }

            JSONArray fields = fields(jsonForm);
            if (fields == null) {
                return null;
            }

            String entityId = getString(jsonForm, ENTITY_ID);
            if (StringUtils.isBlank(entityId)) {
                entityId = generateRandomUUIDString();
            }

            String encounterType = getString(jsonForm, ENCOUNTER_TYPE);
            JSONObject metadata = getJSONObject(jsonForm, METADATA);

            // String lastLocationName = null;
            // String lastLocationId = null;
            // TODO Replace values for location questions with their corresponding location IDs


            JSONObject lastInteractedWith = new JSONObject();
            lastInteractedWith.put(Constants.KEY.KEY, DBConstants.KEY.LAST_INTERACTED_WITH);
            lastInteractedWith.put(Constants.KEY.VALUE, Calendar.getInstance().getTimeInMillis());
            fields.put(lastInteractedWith);
            final String OPTIONS = "options";

            JSONObject dobUnknownObject = getFieldJSONObject(fields, DBConstants.KEY.IS_DATE_OF_BIRTH_UNKNOWN);
            JSONArray options = getJSONArray(dobUnknownObject, OPTIONS);
            JSONObject option = getJSONObject(options, 0);
            String dobUnKnownString = option != null ? option.getString(VALUE) : null;
            if (StringUtils.isNotBlank(dobUnKnownString)) {
                boolean isDateOfBirthUnknown = Boolean.valueOf(dobUnKnownString);
                if (isDateOfBirthUnknown) {
                    String ageString = getFieldValue(fields, DBConstants.KEY.DOB);
                    if (StringUtils.isNotBlank(ageString) && NumberUtils.isNumber(ageString)) {
                        int age = Integer.valueOf(ageString);
                        JSONObject dobJSONObject = getFieldJSONObject(fields, DBConstants.KEY.IS_DATE_OF_BIRTH_UNKNOWN);
                        dobJSONObject.put(VALUE, Utils.getDob(age));
                    }
                }
            }

            FormTag formTag = new FormTag();
            formTag.providerId = providerId;
            formTag.appVersion = BuildConfig.VERSION_CODE;
            formTag.databaseVersion = BuildConfig.DATABASE_VERSION;


            Client baseClient = org.smartregister.util.JsonFormUtils.createBaseClient(fields, formTag, entityId);
            Event baseEvent = org.smartregister.util.JsonFormUtils.createEvent(fields, metadata, formTag, entityId, encounterType, DBConstants.WOMAN_TABLE_NAME);
            return Pair.create(baseClient, baseEvent);
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
            return null;
        }
    }

    public static void mergeAndSaveClient(ECSyncHelper ecUpdater, Client baseClient) throws Exception {
        JSONObject updatedClientJson = new JSONObject(org.smartregister.util.JsonFormUtils.gson.toJson(baseClient));

        JSONObject originalClientJsonObject = ecUpdater.getClient(baseClient.getBaseEntityId());

        JSONObject mergedJson = org.smartregister.util.JsonFormUtils.merge(originalClientJsonObject, updatedClientJson);

        //TODO Save edit log ?

        ecUpdater.addClient(baseClient.getBaseEntityId(), mergedJson);
    }

    public static void saveImage(String providerId, String entityId, String imageLocation) {
        if (StringUtils.isBlank(imageLocation)) {
            return;
        }

        File file = new File(imageLocation);

        if (!file.exists()) {
            return;
        }

        Bitmap compressedImageFile = AncApplication.getInstance().getCompressor().compressToBitmap(file);
        saveStaticImageToDisk(compressedImageFile, providerId, entityId);

    }

    private static void saveStaticImageToDisk(Bitmap image, String providerId, String entityId) {
        if (image == null || StringUtils.isBlank(providerId) || StringUtils.isBlank(entityId)) {
            return;
        }
        OutputStream os = null;
        try {

            if (entityId != null && !entityId.isEmpty()) {
                final String absoluteFileName = DrishtiApplication.getAppDir() + File.separator + entityId + ".JPEG";

                File outputFile = new File(absoluteFileName);
                os = new FileOutputStream(outputFile);
                Bitmap.CompressFormat compressFormat = Bitmap.CompressFormat.JPEG;
                if (compressFormat != null) {
                    image.compress(compressFormat, 100, os);
                } else {
                    throw new IllegalArgumentException("Failed to save static image, could not retrieve image compression format from name "
                            + absoluteFileName);
                }
                // insert into the db
                ProfileImage profileImage = new ProfileImage();
                profileImage.setImageid(UUID.randomUUID().toString());
                profileImage.setAnmId(providerId);
                profileImage.setEntityID(entityId);
                profileImage.setFilepath(absoluteFileName);
                profileImage.setFilecategory("profilepic");
                profileImage.setSyncStatus(ImageRepository.TYPE_Unsynced);
                ImageRepository imageRepo = AncApplication.getInstance().getContext().imageRepository();
                imageRepo.add(profileImage);
            }

        } catch (FileNotFoundException e) {
            Log.e(TAG, "Failed to save static image to disk");
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close static images output stream after attempting to write image");
                }
            }
        }

    }

    public static String getString(String jsonString, String field) {
        return getString(toJSONObject(jsonString), field);
    }

    public static String getFieldValue(String jsonString, String key) {
        JSONObject jsonForm = toJSONObject(jsonString);
        if (jsonForm == null) {
            return null;
        }

        JSONArray fields = fields(jsonForm);
        if (fields == null) {
            return null;
        }

        return getFieldValue(fields, key);

    }

    public static JSONObject getFieldJSONObject(JSONArray jsonArray, String key) {
        if (jsonArray == null || jsonArray.length() == 0) {
            return null;
        }

        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = getJSONObject(jsonArray, i);
            String keyVal = getString(jsonObject, KEY);
            if (keyVal != null && keyVal.equals(key)) {
                return jsonObject;
            }
        }
        return null;
    }

    public static String getAutoPopulatedJsonEditFormString(Context context, Map<String, String> womanClient) {
        try {
            JSONObject form = FormUtils.getInstance(context).getFormJson(Constants.JSON_FORM.ANC_REGISTER);
            LocationPickerView lpv = new LocationPickerView(context);
            lpv.init();
            JsonFormUtils.addWomanRegisterHierarchyQuestions(form);
            Log.d(TAG, "Form is " + form.toString());
            if (form != null) {
                form.put(JsonFormUtils.ENTITY_ID, womanClient.get(DBConstants.KEY.BASE_ENTITY_ID));
                form.put(JsonFormUtils.ENCOUNTER_TYPE, Constants.EventType.UPDATE_REGISTRATION);
                JSONObject metadata = form.getJSONObject(JsonFormUtils.METADATA);
                String lastLocationId = LocationHelper.getInstance().getOpenMrsLocationId(lpv.getSelectedItem());
                metadata.put(JsonFormUtils.ENCOUNTER_LOCATION, lastLocationId);

                form.put(JsonFormUtils.CURRENT_OPENSRP_ID, womanClient.get(DBConstants.KEY.ANC_ID).replace("-", ""));

                //inject opensrp id into the form
                JSONObject stepOne = form.getJSONObject(JsonFormUtils.STEP1);
                JSONArray jsonArray = stepOne.getJSONArray(JsonFormUtils.FIELDS);
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);

                    if (jsonObject.getString(JsonFormUtils.KEY).equalsIgnoreCase(DBConstants.KEY.FIRST_NAME)) {
                        jsonObject.put(JsonFormUtils.VALUE, womanClient.get(DBConstants.KEY.FIRST_NAME));

                    }

                    if (jsonObject.getString(JsonFormUtils.KEY).equalsIgnoreCase(Constants.KEY.PHOTO)) {

                        Photo photo = ImageUtils.profilePhotoByClientID(DBConstants.KEY.BASE_ENTITY_ID);

                        if (StringUtils.isNotBlank(photo.getFilePath())) {

                            jsonObject.put(JsonFormUtils.VALUE, photo.getFilePath());

                        }
                    }

                    if (jsonObject.getString(JsonFormUtils.KEY).equalsIgnoreCase(DBConstants.KEY.LAST_NAME)) {
                        jsonObject.put(JsonFormUtils.VALUE, womanClient.get("last_name"));

                    }
                    if (jsonObject.getString(JsonFormUtils.KEY).equalsIgnoreCase("Sex")) {

                        jsonObject.put(JsonFormUtils.VALUE, womanClient.get(DBConstants.KEY.GENDER));
                    }
                    if (jsonObject.getString(JsonFormUtils.KEY).equalsIgnoreCase(DBConstants.KEY.ANC_ID)) {

                        jsonObject.put(JsonFormUtils.VALUE, womanClient.get(DBConstants.KEY.ANC_ID).replace("-", ""));
                    }

                    if (jsonObject.getString(JsonFormUtils.KEY).equalsIgnoreCase(DBConstants.KEY.ALTERNATE_NAME)) {
                        jsonObject.put(JsonFormUtils.READ_ONLY, false);
                        jsonObject.put(JsonFormUtils.VALUE, womanClient.get(DBConstants.KEY.ALTERNATE_NAME));
                    }
                    if (jsonObject.getString(JsonFormUtils.KEY).equalsIgnoreCase(DBConstants.KEY.ALTERNATE_PHONE)) {
                        jsonObject.put(JsonFormUtils.READ_ONLY, false);
                        jsonObject.put(JsonFormUtils.VALUE, womanClient.get(DBConstants.KEY.ALTERNATE_PHONE));
                    }
                    if (jsonObject.getString(JsonFormUtils.KEY).equalsIgnoreCase(DBConstants.KEY.AGE)) {
                        jsonObject.put(JsonFormUtils.READ_ONLY, false);
                        jsonObject.put(JsonFormUtils.VALUE, womanClient.get(DBConstants.KEY.AGE));
                    }
                    if (jsonObject.getString(JsonFormUtils.KEY).equalsIgnoreCase(DBConstants.KEY.PHONE_NUMBER)) {
                        jsonObject.put(JsonFormUtils.READ_ONLY, false);
                        jsonObject.put(JsonFormUtils.VALUE, womanClient.get(DBConstants.KEY.PHONE_NUMBER));
                    }


                    if (jsonObject.getString(JsonFormUtils.KEY).equalsIgnoreCase(DBConstants.KEY.DOB)) {


                        String dobString = womanClient.get(DBConstants.KEY.DOB);
                        Date dob = Utils.dobStringToDate(dobString);
                        if (dob != null) {
                            jsonObject.put(JsonFormUtils.VALUE, DATE_FORMAT.format(dob));
                        }
                    }

                    if (jsonObject.getString(JsonFormUtils.KEY).equalsIgnoreCase(DBConstants.KEY.HOME_ADDRESS)) {

                        String school = womanClient.get(DBConstants.KEY.HOME_ADDRESS);
                        jsonObject.put(JsonFormUtils.VALUE, school);
                        jsonObject.toString();
                    }

                    if (jsonObject.getString(JsonFormUtils.KEY).equalsIgnoreCase(DBConstants.KEY.HOME_ADDRESS)) {
                        List<String> schoolFacilityHierarchy = new ArrayList<>();
                        String address5 = womanClient.get(DBConstants.KEY.HOME_ADDRESS);
                        schoolFacilityHierarchy.add(address5);

                        String schoolFacilityHierarchyString = AssetHandler.javaToJsonString(schoolFacilityHierarchy, new TypeToken<List<String>>() {
                        }.getType());
                        if (StringUtils.isNotBlank(schoolFacilityHierarchyString)) {
                            jsonObject.put(JsonFormUtils.VALUE, schoolFacilityHierarchyString);
                        }


                    }

                }

                return form.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }

        return "";
    }

    public static void addWomanRegisterHierarchyQuestions(JSONObject form) {
        try {
            JSONArray questions = form.getJSONObject("step1").getJSONArray("fields");
            ArrayList<String> allLevels = new ArrayList<>();
            allLevels.add("Country");
            allLevels.add("Province");
            allLevels.add("District");
            allLevels.add("City/Town");
            allLevels.add("Health Facility");
            allLevels.add(LocationHelper.HOME_ADDRESS);


            ArrayList<String> healthFacilities = new ArrayList<>();
            healthFacilities.add(LocationHelper.HOME_ADDRESS);


            List<String> defaultFacility = LocationHelper.getInstance().generateDefaultLocationHierarchy(healthFacilities);
            List<FormLocation> upToFacilities = LocationHelper.getInstance().generateLocationHierarchyTree(false, healthFacilities);

            String defaultFacilityString = AssetHandler.javaToJsonString(defaultFacility,
                    new TypeToken<List<String>>() {
                    }.getType());

            String upToFacilitiesString = AssetHandler.javaToJsonString(upToFacilities,
                    new TypeToken<List<FormLocation>>() {
                    }.getType());

            for (int i = 0; i < questions.length(); i++) {
                if (questions.getJSONObject(i).getString(Constants.KEY.KEY).equalsIgnoreCase(LocationHelper.HOME_ADDRESS)) {
                    if (StringUtils.isNotBlank(upToFacilitiesString)) {
                        questions.getJSONObject(i).put(Constants.KEY.TREE, new JSONArray(upToFacilitiesString));
                    }
                    if (StringUtils.isNotBlank(defaultFacilityString)) {
                        questions.getJSONObject(i).put(Constants.KEY.DEFAULT, defaultFacilityString);
                    }
                }
            }

        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    public static void startFormForEdit(Activity context, int jsonFormActivityRequestCode, String metaData) {
        Intent intent = new Intent(context, JsonFormActivity.class);
        intent.putExtra("json", metaData);
        Log.d(TAG, "form is " + metaData);
        context.startActivityForResult(intent, jsonFormActivityRequestCode);

    }

}
