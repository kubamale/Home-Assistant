package dev.kuba.homedisplay.settings;

import java.util.List;

public interface GeocodingClient {

    List<Place> search(String query);
}
