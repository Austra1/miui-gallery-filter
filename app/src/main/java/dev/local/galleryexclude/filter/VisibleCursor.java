package dev.local.galleryexclude.filter;

import android.database.AbstractCursor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.List;

/** Owns an unchanged source cursor; only row positions are projected, without copying BLOBs. */
public final class VisibleCursor extends AbstractCursor {
    private final Cursor source;
    private final int[] positions;
    public VisibleCursor(Cursor source, List<Integer> visiblePositions) {
        this.source = source;
        positions = new int[visiblePositions.size()];
        for (int i=0;i<positions.length;i++) positions[i]=visiblePositions.get(i);
    }
    @Override public int getCount(){return positions.length;}
    @Override public boolean onMove(int oldPosition,int newPosition){return source.moveToPosition(positions[newPosition]);}
    @Override public String[] getColumnNames(){return source.getColumnNames();}
    @Override public String getString(int column){checkPosition();return source.getString(column);}
    @Override public short getShort(int column){checkPosition();return source.getShort(column);}
    @Override public int getInt(int column){checkPosition();return source.getInt(column);}
    @Override public long getLong(int column){checkPosition();return source.getLong(column);}
    @Override public float getFloat(int column){checkPosition();return source.getFloat(column);}
    @Override public double getDouble(int column){checkPosition();return source.getDouble(column);}
    @Override public byte[] getBlob(int column){checkPosition();return source.getBlob(column);}
    @Override public int getType(int column){checkPosition();return source.getType(column);}
    @Override public boolean isNull(int column){checkPosition();return source.isNull(column);}
    @Override public Bundle getExtras(){return source.getExtras();}
    @Override public Bundle respond(Bundle extras){return source.respond(extras);}
    @Override public Uri getNotificationUri(){return source.getNotificationUri();}
    @Override public List<Uri> getNotificationUris(){return source.getNotificationUris();}
    @Override public void close(){try{source.close();}finally{super.close();}}
}
