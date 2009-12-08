package org.sfcta.cycletracks;

import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.location.Location;

import com.google.android.maps.OverlayItem;

public class TripData {
	long tripid;
	double startTime = 0;
	Float distanceTraveled = 0.0f;
	int numpoints, lathigh, lgthigh, latlow, lgtlow, latestlat, latestlgt;
	boolean dirty = false;

	private ItemizedOverlayTrack gpspoints;
	Location lastLocation;
	float curSpeed, maxSpeed;

	DbAdapter mDb;
	RecordingActivity recordActivity = null;

	public static TripData createTrip(Context c) {
		TripData t = new TripData(c.getApplicationContext(), 0);
		t.createTripInDatabase(c);
        t.initializeData();
		return t;
	}

	public static TripData fetchTrip(Context c, long tripid) {
		TripData t = new TripData(c.getApplicationContext(), tripid);
		t.populateDetails();
		return t;
	}

	public TripData (Context ctx, long tripid) {
		Context context = ctx.getApplicationContext();
		this.tripid = tripid;
		mDb = new DbAdapter(context);
	}

	void initializeData() {
		startTime = System.currentTimeMillis();
        curSpeed = maxSpeed = distanceTraveled = 0.0f;
        numpoints = 0;
        dirty = false;
        latestlat = 0; latestlgt = 0;

        lathigh = (int) (-100 * 1E6);
		latlow = (int) (100 * 1E6);
		lgtlow = (int) (360 * 1E6);
		lgthigh = (int) (-360 * 1E6);

		updateTrip();
	}

    // Get lat/long extremes, etc, from trip record
	void populateDetails() {

	    mDb.openReadOnly();

	    Cursor tripdetails = mDb.fetchTrip(tripid);
	    startTime = tripdetails.getDouble(tripdetails.getColumnIndex("start"));
	    lathigh = tripdetails.getInt(tripdetails.getColumnIndex("lathi"));
	    latlow =  tripdetails.getInt(tripdetails.getColumnIndex("latlo"));
	    lgthigh = tripdetails.getInt(tripdetails.getColumnIndex("lgthi"));
	    lgtlow =  tripdetails.getInt(tripdetails.getColumnIndex("lgtlo"));
	    tripdetails.close();

	    mDb.close();
	}

	void createTripInDatabase(Context c) {
		mDb.open();
		tripid = mDb.createTrip();
		mDb.close();
	}

	void dropTrip() {
	    mDb.open();
		mDb.deleteAllCoordsForTrip(tripid);
		mDb.deleteTrip(tripid);
		mDb.close();
	}

	void registerUpdates(RecordingActivity ra) {
		this.recordActivity = ra;
	}

	public ItemizedOverlayTrack getPoints(Drawable d) {
		// If already built, don't build again!
		if (gpspoints != null && gpspoints.size()>0) {
			return gpspoints;
		}

		// Otherwise, we need to query DB and build points from scratch.
		gpspoints = new ItemizedOverlayTrack(d);

		try {
			mDb.openReadOnly();

			Cursor points = mDb.fetchAllCoordsForTrip(tripid);
            int COL_LAT = points.getColumnIndex("lat");
            int COL_LGT = points.getColumnIndex("lgt");
            int COL_TIME = points.getColumnIndex("time");
            numpoints = points.getCount();

			while (!points.isAfterLast()) {
                int lat = points.getInt(COL_LAT);
                int lgt = points.getInt(COL_LGT);
                double time = points.getDouble(COL_TIME);

                addPointToSavedMap(lat, lgt, time);
                // if (gpspoints.size() % 100 == 99) publishProgress(pk);

				points.moveToNext();
			}
			points.close();
			mDb.close();

		} catch (Exception e) {
			e.printStackTrace();
		}
		gpspoints.repopulate();

		return gpspoints;
	}

	private void addPointToSavedMap(int lat, int lgt, double currentTime) {
		CyclePoint pt = new CyclePoint(lat, lgt, currentTime);

		OverlayItem opoint = new OverlayItem(pt, null, null);
		gpspoints.addOverlay(opoint);
	}

    private void updateTripStats(Location newLocation) {
        final float spdConvert = 2.2369f;
        if (lastLocation != null) {
        	// Some stats should only be updated if accuracy is decent
        	if (newLocation.getAccuracy()< 75) {
                float segmentDistance = lastLocation.distanceTo(newLocation);
                distanceTraveled = distanceTraveled.floatValue() + segmentDistance;
                curSpeed = newLocation.getSpeed() * spdConvert;
                // And, speed calcs are sometimes awful, too
                if (curSpeed < 60.0f) {
                	maxSpeed = Math.max(maxSpeed, curSpeed);
                }
                lastLocation = newLocation;
        	}

            // Save the point no matter what, even with bad accuracy
            numpoints++;
            updateTrip();
        }
    }

	void addPointNow(Location loc, double currentTime) {
		int lat = (int) (loc.getLatitude() * 1E6);
		int lgt = (int) (loc.getLongitude() * 1E6);
		float accuracy = loc.getAccuracy();
		double altitude = loc.getAltitude();
		float speed = loc.getSpeed();

		// Skip duplicates
		if (latestlat == lat && latestlgt == lgt)
			return;

		CyclePoint pt = new CyclePoint(lat, lgt, currentTime, accuracy,
				altitude, speed);

        dirty = true;
        numpoints++;

		latlow = Math.min(latlow, lat);
		lathigh = Math.max(lathigh, lat);
		lgtlow = Math.min(lgtlow, lgt);
		lgthigh = Math.max(lgthigh, lgt);

		latestlat = lat;
		latestlgt = lgt;

		updateTripStats(loc);

        mDb.open();
        mDb.addCoordToTrip(tripid, pt);
        mDb.updateTrip(tripid, "", startTime, "", "",
                lathigh, latlow, lgthigh, lgtlow);
        mDb.close();


		if (recordActivity != null) {
			try {
				recordActivity.updateStatus();
			} catch (Exception e) {}
		}
	}

	public void updateTrip() {
	    updateTrip("","","");
	}

	public void updateTrip(String purpose, String fancy, String notes) {
		// Save the trip details to the phone database. W00t!
		mDb.open();
		mDb.updateTrip(tripid, purpose,	startTime, fancy, notes,
				lathigh, latlow, lgthigh, lgtlow);
		mDb.close();
	}
}
