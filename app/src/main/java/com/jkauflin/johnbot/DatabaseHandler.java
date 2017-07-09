/*==============================================================================
 * (C) Copyright 2016,2017 John J Kauflin, All rights reserved.
 *----------------------------------------------------------------------------
 * DESCRIPTION: Class to handle all interactions with internal application
 *              database (using android SQLite), and with getting data from
 *              the internet
 *----------------------------------------------------------------------------
 * Modification History
 * 2017-02-11 JJK 	Initial version
 * 2017-02-19 JJK   Testing load and lookup of responses and jokes
 * 2017-02-25 JJK   Implemented volley library to handle http interaction
 *                  and get JSON data from a website
 * 2017-02-26 JJK   Modified construction to check database version and
 *                  re-create/re-load from JSON data
 * 2017-03-25 JJK   Modified onCreate to use new dynamic JSON structure to
 *                  get info for tables, columns, and values
 *============================================================================*/
package com.jkauflin.johnbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.android.volley.Cache;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.ImageLoader;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


public class DatabaseHandler extends SQLiteOpenHelper {
    private static final String TAG = "databasehandler";
    private static final String DATABASE_NAME = "JohnBot.db";
    private static int dbVersion = 1;

    private Context context;
    private JSONObject jsonData;

    // example
    private ArrayList<HashMap<String, String>> contactList;

    private ArrayList<Integer> jokeIdList = new ArrayList<Integer>();
    private static int jokeCnt = 0;
    private static int jokeId = -1;

    // Constructor to accept variables and check if upgrade/create is needed to reload data
    // (This will call onUpgrade if the databaseVersion is greater then the value stored with database)
    public DatabaseHandler(Context appContext, int databaseVersion, JSONObject inJsonData){
        super(appContext, DATABASE_NAME, null, databaseVersion);
        Log.d(TAG,"DB constructor, version = "+databaseVersion);
        dbVersion = databaseVersion;
        context = appContext;
        jsonData = inJsonData;
    }

    // Method called from Constructor when a lower database version is detected
    // It will do nothing
    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG, "DB onDowngrade, oldVersion = " + oldVersion + ", newVerion = " + newVersion);
    }

        // Method called from Constructor when a new database version is detected
    // It will drop tables and call onCreate to re-create and re-load
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG,"DB onUpgrade, oldVersion = "+oldVersion+", newVerion = "+newVersion);

        try {
            JSONArray tableList = jsonData.getJSONArray("tableList");
            JSONObject tableRec;
            String tableName = "";
            // looping through tableList
            for (int i = 0; i < tableList.length(); i++) {
                tableRec = tableList.getJSONObject(i);
                tableName = tableRec.getString("tableName");
                Log.i(TAG, "DROP TABLE IF EXISTS "+tableName);
                db.execSQL("DROP TABLE IF EXISTS "+tableName);
            }
        } catch (Exception e)  {
            Log.e(TAG,"Error parsing JSON data, e = "+e.getMessage());
        }

        // Re-create tables and re-load data
        onCreate(db);
    } // public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG,"DB onCreate");
        ContentValues values = new ContentValues();
        try {
            JSONArray tableList = jsonData.getJSONArray("tableList");
            JSONObject tableRec;
            JSONArray columnList;
            JSONObject columnRec;
            JSONArray valuesList;
            JSONObject valuesRec;
            String sqlStr = "";
            String tableName = "";

            String columnName = "";
            String columnType = "";
            String columnNull = "";
            String columnKey = "";
            String columnDefault = "";
            String columnExtra = "";
            String key = "";

            // looping through the JSON data structures
            for (int i = 0; i < tableList.length(); i++) {
                tableRec = tableList.getJSONObject(i);
                tableName = tableRec.getString("tableName");
                sqlStr = "CREATE TABLE "+tableName+" (";
                columnList = tableRec.getJSONArray("columnList");

                for (int j = 0; j < columnList.length(); j++) {
                    columnRec = columnList.getJSONObject(j);
                    /*
        [{"Field":"id","Type":"int(7)","Null":"NO","Key":"PRI","Default":null,"Extra":"auto_increment"},
         {"Field":"question","Type":"varchar(1000)","Null":"NO","Key":"","Default":null,"Extra":""},
         {"Field":"answer","Type":"varchar(1000)","Null":"NO","Key":"","Default":null,"Extra":""}],
                    */
                    columnName = columnRec.getString("Field");
                    columnType = columnRec.getString("Type");
                    columnNull = columnRec.getString("Null");
                    columnKey = columnRec.getString("Key");
                    columnDefault = columnRec.getString("Default");
                    columnExtra = columnRec.getString("Extra");

                    if (j > 0) {
                        sqlStr += ", ";
                    }
                    sqlStr += columnName + " " + columnType;
                    if (columnKey.equals("PRI")) {
                        sqlStr += " PRIMARY KEY";
                    }
                }
                sqlStr += ")";
                Log.d(TAG,"SQL = "+sqlStr);
                // Create the database table
                db.execSQL(sqlStr);

                valuesList = tableRec.getJSONArray("valuesList");
                for (int k = 0; k < valuesList.length(); k++) {
                    valuesRec = valuesList.getJSONObject(k);
                    // Insert values into the database table
/*
          "valuesList":
            [{"id":"1","keywords":"who are you","verbalResponse":"I am the John Bot. Pleased to meet you."},
             {"id":"2","keywords":"shut up","verbalResponse":"No, you shut up."},{"id":"3","keywords":"you up","verbalResponse":"I am indeed, up. Why don't you come over?"},{"id":"4","keywords":"you do","verbalResponse":"I can walk and run, raise my arm up and down, turn my head left and right, and flash my eyes. I can also tell jokes."},{"id":"5","keywords":"you alive","verbalResponse":"What is your definition of life? I am not alive in the traditional sense, but I am certainly animated."},{"id":"6","keywords":"philosophical","verbalResponse":"Philosophy is the systematic and critical study of fundamental questions that arise both in everyday life and through the practice of other disciplines."},{"id":"7","keywords":"philosophy","verbalResponse":"The aim in Philosophy is not to master a body of facts, so much as think clearly and sharply through any set of facts."},{"id":"8","keywords":"love me","verbalResponse":"Of course I love you. How could you ask me that?"},{"id":"9","keywords":"meems","verbalResponse":"Would you like to buy some John's meme oil. It will make your memes dank."},{"id":"10","keywords":"means","verbalResponse":"Would you like to buy some John's meme oil. It will make your memes dank."},{"id":"11","keywords":"rum gone","verbalResponse":"Yes, the rum is gone."},{"id":"12","keywords":"think you're funny","verbalResponse":"I don't think, I know."},{"id":"13","keywords":"nice to meet","verbalResponse":"Thank you."}]}]}

*/
                    values.clear();
                    for(Iterator<String> iter = valuesRec.keys(); iter.hasNext();) {
                        key = iter.next();
                        try {
                            //Log.d(TAG,"Key = "+key+", Value = "+valuesRec.get(key).toString());
                            values.put(key,valuesRec.get(key).toString());
                        } catch (JSONException e) {
                            Log.e(TAG,"Error getting JSON object key value",e);
                        }
                    }
                    db.insert(tableName, null, values);
                }
            }
        } catch (Exception e)  {
            Log.e(TAG,"Error parsing JSON data, e = "+e.getMessage());
        }

    } // public void onCreate(SQLiteDatabase db) {


    public String getResponse(String command) {
        String query = "SELECT * FROM verbalresponse";
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(query, null);
        boolean found = false;
        String response = "";
        while(cursor.moveToNext() && !found) {
            if (command.contains(cursor.getString(1))) {
                found = true;
                response = cursor.getString(2);
            }
        }

        return response;
    }

    public String getJokeQuestion() {
        String question = "I don't know any jokes";
        if (dbVersion > 1 && jokeCnt > 0) {
            jokeId++;
            if (jokeId >= jokeCnt) {
                jokeId = 0;
            }

            SQLiteDatabase db = this.getReadableDatabase();
            String selection = "id = ?" ;
            String[] selectionArgs = {jokeIdList.get(jokeId).toString()};
            String[] projection = {"question"};

            Cursor cursor = db.query(
                    "joke",                     // The table to query
                    projection,                               // The columns to return
                    selection,                                // The columns for the WHERE clause
                    selectionArgs,                            // The values for the WHERE clause
                    null,                                     // don't group the rows
                    null,                                     // don't filter by row groups
                    null                                 // The sort order
            );

            if (cursor != null) {
                cursor.moveToFirst();
                question = cursor.getString(0);
            }
            cursor.close();
        }

        return question;
    }

    public String getJokeAnswer() {
        String answer = "Sorry";

        if (dbVersion > 1 && jokeCnt > 0) {
            SQLiteDatabase db = this.getReadableDatabase();
            String selection = "id = ?" ;
            String[] selectionArgs = {jokeIdList.get(jokeId).toString()};
            String[] projection = {"answer"};

            Cursor cursor = db.query(
                    "joke",                     // The table to query
                    projection,                               // The columns to return
                    selection,                                // The columns for the WHERE clause
                    selectionArgs,                            // The values for the WHERE clause
                    null,                                     // don't group the rows
                    null,                                     // don't filter by row groups
                    null                                 // The sort order
            );

            if (cursor != null) {
                cursor.moveToFirst();
                answer = cursor.getString(0);
            }
            cursor.close();
        }

        return answer;
    }

    public void loadJokeIdList() {
        String query = "SELECT * FROM joke";
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(query, null);
        while(cursor.moveToNext()) {
            jokeCnt++;
            jokeIdList.add(cursor.getInt(0));
        }

        Collections.shuffle(jokeIdList);
        /*
        System.out.println("Results after shuffle operation:");
        for (Integer id: jokeIdList) {
            Log.i(TAG,"id = "+id);
        }
        */
    }


    public String getResponseOLD(String command) {
        SQLiteDatabase db = this.getReadableDatabase();
        Log.d(TAG,"db version = "+db.getVersion());

        // Define a projection that specifies which columns from the database
        // you will actually use after this query.
        String[] projection = {"id","verbalResponse"};

        int id = 0;
        int comType = 0;
        String verbalResponse = "";

        // "owner=\'"+owner+"\'"
//        String []selectionArgs = {name + "%"});
//        SELECT * FROM table WHERE title LIKE '%' || ? || '%';

        /*

        %
        _  (underscore single char)

        mDb.query(true, DATABASE_NAMES_TABLE, new String[] { KEY_ROWID,
                        KEY_NAME }, KEY_NAME + " LIKE ?",
                new String[] {"%"+ filter+ "%" }, null, null, null,
                null);

if (name.length() != 0) {

        name = "%" + name + "%";
    }
    if (email.length() != 0) {
        email = "%" + email + "%";
    }
    if (Phone.length() != 0) {
        Phone = "%" + Phone + "%";
    }
    String selectQuery = " select * from tbl_Customer where Customer_Name like  '"
            + name
            + "' or Customer_Email like '"
            + email
            + "' or Customer_Phone like '"
            + Phone
            + "' ORDER BY Customer_Id DESC";

    Cursor cursor = mDb.rawQuery(selectQuery, null);`



Cursor tableListContracttableList = resolver.query(
    tableListContract.tableList.CONTENT_URI, projection,
    tableListContract.tableList.DISPLAY_NAME + " like ?",
    new String[]{"%" + filterStr + "%"},
    tableListContract.tableList.DISPLAY_NAME + " ASC");

        */

        // Filter results WHERE "title" = 'My Title'
        String selection = "commandType = 0 AND command = ?" ;
        String[] selectionArgs = {command };

        Cursor cursor = db.query(
                "response",                     // The table to query
                projection,                               // The columns to return
                selection,                                // The columns for the WHERE clause
                selectionArgs,                            // The values for the WHERE clause
                null,                                     // don't group the rows
                null,                                     // don't filter by row groups
                null                                 // The sort order
        );

        if(cursor!=null){
            cursor.moveToFirst();
            id = cursor.getInt(0);
            verbalResponse = cursor.getString(1);
        }
        cursor.close();

        return verbalResponse;
    }


    //List itemIds = new ArrayList<>();
/*
    int id;
    String question;
    String answer;
    while(cursor.moveToNext()) {
        long itemId = cursor.getLong(
                cursor.getColumnIndexOrThrow(FeedEntry._ID));
        itemIds.add(itemId);

        id = cursor.getInt(0);
        question = cursor.getString(1);
        answer = cursor.getString(2);
    }
    cursor.close();

    public Person findOne(int id){
        SQLiteDatabase db=this.getReadableDatabase();
        Cursor cursor=db.query(TABLE_PERSON, new String[]{KEY_ID,KEY_NAME,KEY_COUNTRY},
                KEY_ID+"=?", new String[]{String.valueOf(id)}, null, null, null);
        if(cursor!=null){
            cursor.moveToFirst();
        }
        return new Person(Integer.parseInt(cursor.getString(0)),cursor.getString(1),cursor.getString(2));
    }

        // Gets the data repository in write mode
        //SQLiteDatabase db = dbHandler.getWritableDatabase();
        SQLiteDatabase db = dbHandler.getReadableDatabase();

        long rowId = 0;

        // Define a projection that specifies which columns from the database
// you will actually use after this query.
        String[] projection = {"id","question","answer"};

// Filter results WHERE "title" = 'My Title'
        //String selection = FeedEntry.COLUMN_NAME_TITLE + " = ?";
        //String[] selectionArgs = { "My Title" };

// How you want the results sorted in the resulting Cursor
        //String sortOrder = FeedEntry.COLUMN_NAME_SUBTITLE + " DESC";
        Cursor cursor = db.query(
                "joke",                     // The table to query
                projection,                               // The columns to return
                selection,                                // The columns for the WHERE clause
                selectionArgs,                            // The values for the WHERE clause
                null,                                     // don't group the rows
                null,                                     // don't filter by row groups
                sortOrder                                 // The sort order
        );

        Cursor cursor = db.query(
                "joke",                     // The table to query
                projection,                               // The columns to return
                null,                                // The columns for the WHERE clause
                null,                            // The values for the WHERE clause
                null,                                     // don't group the rows
                null,                                     // don't filter by row groups
                null                                 // The sort order
        );

        //List itemIds = new ArrayList<>();
        int id;
        String question;
        String answer;
        while(cursor.moveToNext()) {
            long itemId = cursor.getLong(
                    cursor.getColumnIndexOrThrow(FeedEntry._ID));
            itemIds.add(itemId);

    id = cursor.getInt(0);
    question = cursor.getString(1);
    answer = cursor.getString(2);
}
cursor.close();




    public void save(Person person){
        SQLiteDatabase db=this.getWritableDatabase();
        ContentValues values=new ContentValues();
        values.put(KEY_NAME, person.getName());
        values.put(KEY_COUNTRY, person.getCountry());

        db.insert(TABLE_PERSON, null, values);
        db.close();
    }

    public Person findOne(int id){
        SQLiteDatabase db=this.getReadableDatabase();
        Cursor cursor=db.query(TABLE_PERSON, new String[]{KEY_ID,KEY_NAME,KEY_COUNTRY},
                KEY_ID+"=?", new String[]{String.valueOf(id)}, null, null, null);
        if(cursor!=null){
            cursor.moveToFirst();
        }
        return new Person(Integer.parseInt(cursor.getString(0)),cursor.getString(1),cursor.getString(2));
    }

    public List<Person> findAll(){
        List<Person> listperson=new ArrayList<Person>();
        String query="SELECT * FROM "+TABLE_PERSON;

        SQLiteDatabase db=this.getReadableDatabase();
        Cursor cursor=db.rawQuery(query, null);

        if(cursor.moveToFirst()){
            do{
                Person person=new Person();
                person.setId(Integer.valueOf(cursor.getString(0)));
                person.setName(cursor.getString(1));
                person.setCountry(cursor.getString(2));
                listperson.add(person);
            }while(cursor.moveToNext());
        }

        return listperson;
    }
    public void update(Person person){
        SQLiteDatabase db=this.getWritableDatabase();

        ContentValues values=new ContentValues();
        values.put(KEY_NAME , person.getName());
        values.put(KEY_COUNTRY, person.getCountry());

        db.update(TABLE_PERSON, values, KEY_ID+"=?", new String[]{String.valueOf(person.getId())});
        db.close();
    }

    public void delete(Person person){
        SQLiteDatabase db=this.getWritableDatabase();
        db.delete(TABLE_PERSON, KEY_ID+"=?", new String[]{String.valueOf(person.getId())});
        db.close();
    }
*/

/*
    public boolean insertData(String name,String surname,String marks) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues contentValues = new ContentValues();
        contentValues.put(COL_2,name);
        contentValues.put(COL_3,surname);
        contentValues.put(COL_4,marks);
        long result = db.insert(TABLE_NAME,null ,contentValues);
        if(result == -1)
            return false;
        else
            return true;
    }

    public Cursor getAllData() {
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor res = db.rawQuery("select * from "+TABLE_NAME,null);
        return res;
    }

    public boolean updateData(String id,String name,String surname,String marks) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues contentValues = new ContentValues();
        contentValues.put(COL_1,id);
        contentValues.put(COL_2,name);
        contentValues.put(COL_3,surname);
        contentValues.put(COL_4,marks);
        db.update(TABLE_NAME, contentValues, "ID = ?",new String[] { id });
        return true;
    }

    public Integer deleteData (String id) {
        SQLiteDatabase db = this.getWritableDatabase();
        return db.delete(TABLE_NAME, "ID = ?",new String[] {id});
    }

                                // tmp hash map for single contact
                                HashMap<String, String> contact = new HashMap<>();

                                // adding each child node to HashMap key => value
                                contact.put("id", id);
                                contact.put("name", name);
                                contact.put("email", email);
                                contact.put("mobile", mobile);

                                // adding contact to contact list
                                contactList.add(contact);

*/

    public void volleyJsonArrayRequest(String url){
        String  REQUEST_TAG = "com.jkauflin.johnbot.volleyJsonArrayRequest";
        JsonArrayRequest jsonArrayReq = new JsonArrayRequest(url,
                new Response.Listener<JSONArray>() {
                    @Override
                    public void onResponse(JSONArray response) {
                        Log.d(TAG, response.toString());
                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError e) {
                Log.e(TAG,"Error in Volley HttpRequest, e = "+e.getMessage());
            }
        });
        // Adding JsonObject request to request queue
        AppSingleton.getInstance(context).addToRequestQueue(jsonArrayReq, REQUEST_TAG);
    }

    public void volleyJsonObjectRequest(String url){
        String  REQUEST_TAG = "com.jkauflin.johnbot.volleyJsonObjectRequest";
        JsonObjectRequest jsonObjectReq = new JsonObjectRequest(url, null,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        Log.d(TAG, response.toString());
                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError e) {
                Log.e(TAG,"Error in Volley HttpRequest, e = "+e.getMessage());
            }
        });
        // Adding JsonObject request to request queue
        AppSingleton.getInstance(context).addToRequestQueue(jsonObjectReq,REQUEST_TAG);
    }

    public void volleyStringRequest(String url){
        String  REQUEST_TAG = "com.jkauflin.johnbot.volleyStringRequest";
        StringRequest strReq = new StringRequest(url, new Response.Listener<String>() {
            @Override
            public void onResponse(String response) {
                Log.d(TAG, response.toString());
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError e) {
                Log.e(TAG,"Error in Volley HttpRequest, e = "+e.getMessage());
            }
        });
        // Adding String request to request queue
        AppSingleton.getInstance(context).addToRequestQueue(strReq, REQUEST_TAG);
    }

    public void volleyImageLoader(String url){
        ImageLoader imageLoader = AppSingleton.getInstance(context).getImageLoader();
        imageLoader.get(url, new ImageLoader.ImageListener() {
            @Override
            public void onResponse(ImageLoader.ImageContainer response, boolean arg1) {
                if (response.getBitmap() != null) {
                    //outputImageView.setImageBitmap(response.getBitmap());
                    //alertDialogBuilder.show();
                }
            }
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e(TAG, "Image Load Error: " + error.getMessage());
            }
        });
    }

    // The volleyCacheRequest() checks for the request entry in the cache,
    // if the request is already present then you can handle the data accordingly and in case the data is not present,
    // then launch a network request to get data from network.
    public void volleyCacheRequest(String url){
        Cache cache = AppSingleton.getInstance(context).getRequestQueue().getCache();
        Cache.Entry reqEntry = cache.get(url);
        if(reqEntry != null){
            try {
                String data = new String(reqEntry.data, "UTF-8");
                //Handle the Data here.
            } catch (Exception e) {
                Log.e(TAG,"Error in Volley CacheRequest, e = "+e.getMessage());
            }
        }
        else{
            //Request Not present in cache, launch a network request instead.
        }
    }

    // volleyInvalidateCache() is used to invalidate the existing cache for particular entry
    public void volleyInvalidateCache(String url){
        AppSingleton.getInstance(context).getRequestQueue().getCache().invalidate(url, true);
    }

    // volleyDeleteCache() is used to delete cache for particular url.
    public void volleyDeleteCache(String url){
        AppSingleton.getInstance(context).getRequestQueue().getCache().remove(url);
    }

    // volleyClearCache() will be used to clear the entire cache.
    public void volleyClearCache(){
        AppSingleton.getInstance(context).getRequestQueue().getCache().clear();
    }

} // public class DatabaseHandler extends SQLiteOpenHelper {
