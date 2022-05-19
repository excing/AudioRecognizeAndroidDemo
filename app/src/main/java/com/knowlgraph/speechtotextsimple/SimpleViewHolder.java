package com.knowlgraph.speechtotextsimple;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class SimpleViewHolder extends RecyclerView.ViewHolder {
    private final Map<Integer, View> viewMap;

    public SimpleViewHolder(@NonNull @NotNull View itemView) {
        super(itemView);
        viewMap = new HashMap<>();
    }

    public <T extends View> T get(Integer id) {
        View v = viewMap.get(id);
        if (v == null) {
            v = itemView.findViewById(id);
            viewMap.put(id, v);
        }
        return (T) v;
    }
}
