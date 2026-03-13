package com.example.emergencysafetyapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EmergencyAdapter(private val history: List<String>) :
    RecyclerView.Adapter<EmergencyAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val historyText: TextView = view.findViewById(R.id.historyText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = history.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.historyText.text = history[position]
    }
}