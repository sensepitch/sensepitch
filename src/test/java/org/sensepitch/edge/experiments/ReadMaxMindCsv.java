package org.sensepitch.edge.experiments;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.sensepitch.edge.AnyVersionIpLookup;
import org.sensepitch.edge.TrieIpLabelLookup;

/**
 * Check how long it takes to read the MaxMind CSV into the lookup structure.
 *
 * @author Jens Wilke
 */
public class ReadMaxMindCsv {

  static final CSVFormat FORMAT =
      CSVFormat.DEFAULT
          .builder()
          .setHeader()
          .setSkipHeaderRecord(true)
          .setTrim(true)
          .setQuote('"')
          .get();

  public static void main(String[] args) throws IOException {
    TrieIpLabelLookup jointLookup = new TrieIpLabelLookup();
    final String geoliteDirectory =
        System.getenv("HOME") + "/proj/maxmind-geolite2/GeoLite2-ASN-CSV-latest";
    var in = new FileReader(geoliteDirectory + "/GeoLite2-ASN-Blocks-IPv4.csv");
    var lookup = jointLookup.getIpv4LookupStructure();
    readMaxmindGeolite2AsnCsv(in, jointLookup.getIpv4LookupStructure());
    in = new FileReader(geoliteDirectory + "/GeoLite2-ASN-Blocks-IPv6.csv");
    readMaxmindGeolite2AsnCsv(in, jointLookup.getIpv4LookupStructure());
    String geoliteCountryDirectory =
        System.getenv("HOME") + "/proj/maxmind-geolite2/GeoLite2-Country-CSV-latest";
    in = new FileReader(geoliteCountryDirectory + "/GeoLite2-Country-Locations-de.csv");
    var geoname2Country = readGeonameId2Country(in);
    in = new FileReader(geoliteCountryDirectory + "/GeoLite2-Country-Blocks-IPv4.csv");
    readMaxmindGeolite2CountryCsv(geoname2Country, in, jointLookup.getIpv4LookupStructure());
    in = new FileReader(geoliteCountryDirectory + "/GeoLite2-Country-Blocks-IPv6.csv");
    readMaxmindGeolite2CountryCsv(geoname2Country, in, jointLookup.getIpv6LookupStructure());
    System.out.println("Nodes: " + jointLookup.getNodeCount());
  }

  private static Map<String, String> readGeonameId2Country(Reader in) throws IOException {
    var result = new HashMap<String, String>();
    var parser = CSVParser.parse(in, FORMAT);
    for (CSVRecord record : parser) {
      String geonameId = record.get("geoname_id");
      if (geonameId == null || geonameId.isEmpty()) {
        continue;
      }
      String countryCode = record.get("country_iso_code");
      String continentCode = record.get("continent_code");
      result.put(geonameId, countryCode + "_" + continentCode);
    }
    return result;
  }

  private static void readMaxmindGeolite2CountryCsv(
      Map<String, String> id2country, Reader in, AnyVersionIpLookup lookup) throws IOException {
    var parser = CSVParser.parse(in, FORMAT);
    for (CSVRecord record : parser) {
      String cidr = record.get("network");
      String geonameId = record.get("geoname_id");
      lookup.insert(cidr, "country=" + id2country.get(geonameId));
    }
  }

  private static void readMaxmindGeolite2AsnCsv(Reader in, AnyVersionIpLookup lookup)
      throws IOException {
    var parser = CSVParser.parse(in, FORMAT);
    for (CSVRecord record : parser) {
      String cidr = record.get("network");
      String asn = record.get("autonomous_system_number");
      lookup.insert(cidr, "asn=" + asn);
    }
  }
}
