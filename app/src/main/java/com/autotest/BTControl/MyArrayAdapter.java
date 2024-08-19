package com.autotest.BTControl;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

public class MyArrayAdapter extends ArrayAdapter<String> {

    public MyArrayAdapter(Context context, List<String> devices) {
        super(context, 0, devices);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        // Inflate the view if it does not exist
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item, parent, false);
        }

        // Ensure that you correctly reference the TextView
        TextView deviceAddressTextView = convertView.findViewById(R.id.deviceAddressTextView);

        // Get the current device name
        String deviceAddress = getItem(position);

        // Set the device name to the TextView
        deviceAddressTextView.setText(deviceAddress);

        return convertView;
    }
}

