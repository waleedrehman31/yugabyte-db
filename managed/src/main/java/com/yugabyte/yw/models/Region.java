// Copyright (c) Yugabyte, Inc.
package com.yugabyte.yw.models;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yugabyte.yw.common.YWServiceException;
import io.ebean.Query;
import io.ebean.*;
import io.ebean.annotation.DbJson;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import play.data.validation.Constraints;
import play.libs.Json;

import javax.persistence.*;
import java.util.*;

import static com.yugabyte.yw.models.helpers.CommonUtils.maskConfigNew;
import static io.ebean.Ebean.*;
import static play.mvc.Http.Status.BAD_REQUEST;

@Entity
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel(
    description =
        "Region within a given provider. Typically this will map to a "
            + "single cloud provider region")
public class Region extends Model {
  private static final String SECURITY_GROUP_KEY = "sg_id";
  private static final String VNET_KEY = "vnet";

  @Id public UUID uuid;

  @Column(length = 25, nullable = false)
  @ApiModelProperty(value = "Cloud provider region code", example = "us-west-2", required = true)
  public String code;

  @Column(length = 100, nullable = false)
  @Constraints.Required
  public String name;

  // The AMI to be used in this region.
  @Constraints.Required public String ybImage;

  @Column(columnDefinition = "float")
  public double longitude = -90;

  @Column(columnDefinition = "float")
  public double latitude = -90;

  public void setLatLon(double latitude, double longitude) {
    if (latitude < -90 || latitude > 90) {
      throw new IllegalArgumentException("Invalid Latitude Value, it should be between -90 to 90");
    }
    if (longitude < -180 || longitude > 180) {
      throw new IllegalArgumentException(
          "Invalid Longitude Value, it should be between -180 to 180");
    }

    this.latitude = latitude;
    this.longitude = longitude;
    this.save();
  }

  @Constraints.Required
  @Column(nullable = false)
  @ManyToOne
  @JsonBackReference
  public Provider provider;

  @OneToMany(cascade = CascadeType.ALL)
  public Set<AvailabilityZone> zones;

  @Column(nullable = false, columnDefinition = "boolean default true")
  public Boolean active = true;

  public Boolean isActive() {
    return active;
  }

  public void setActiveFlag(Boolean active) {
    this.active = active;
  }

  @DbJson
  @Column(columnDefinition = "TEXT")
  public JsonNode details;

  public void setSecurityGroupId(String securityGroupId) {
    if (details == null) {
      details = Json.newObject();
    }
    ((ObjectNode) details).put(SECURITY_GROUP_KEY, securityGroupId);
    save();
  }

  public String getSecurityGroupId() {
    if (details != null) {
      JsonNode sgNode = details.get(SECURITY_GROUP_KEY);
      return sgNode == null || sgNode.isNull() ? null : sgNode.asText();
    }
    return null;
  }

  public void setVnetName(String vnetName) {
    if (details == null) {
      details = Json.newObject();
    }
    ((ObjectNode) details).put(VNET_KEY, vnetName);
    save();
  }

  public String getVnetName() {
    if (details != null) {
      JsonNode vnetNode = details.get(VNET_KEY);
      return vnetNode == null || vnetNode.isNull() ? null : vnetNode.asText();
    }
    return null;
  }

  @DbJson
  @Column(columnDefinition = "TEXT")
  public JsonNode config;

  public void setConfig(Map<String, String> configMap) {
    Map<String, String> currConfig = this.getConfig();
    for (String key : configMap.keySet()) {
      currConfig.put(key, configMap.get(key));
    }
    this.config = Json.toJson(currConfig);
    this.save();
  }

  @JsonProperty("config")
  public Map<String, String> getMaskedConfig() {
    return maskConfigNew(getConfig());
  }

  @JsonIgnore
  public Map<String, String> getConfig() {
    if (this.config == null) {
      return new HashMap<>();
    } else {
      return Json.fromJson(this.config, Map.class);
    }
  }

  /** Query Helper for PlacementRegion with region code */
  public static final Finder<UUID, Region> find = new Finder<UUID, Region>(Region.class) {};

  /**
   * Create new instance of PlacementRegion
   *
   * @param provider Cloud Provider
   * @param code Unique PlacementRegion Code
   * @param name User Friendly PlacementRegion Name
   * @param ybImage The YB image id that we need to use for provisioning in this region
   * @return instance of PlacementRegion
   */
  public static Region create(Provider provider, String code, String name, String ybImage) {
    return create(provider, code, name, ybImage, 0.0, 0.0);
  }
  // Overload create function with lat, long values for OnPrem case
  public static Region create(
      Provider provider,
      String code,
      String name,
      String ybImage,
      double latitude,
      double longitude) {
    Region region = new Region();
    region.provider = provider;
    region.code = code;
    region.name = name;
    region.ybImage = ybImage;
    region.latitude = latitude;
    region.longitude = longitude;
    region.save();
    return region;
  }

  public static Region createWithMetadata(Provider provider, String code, JsonNode metadata) {
    Region region = Json.fromJson(metadata, Region.class);
    region.provider = provider;
    region.code = code;
    region.save();
    return region;
  }

  /** DEPRECATED: use {@link #getOrBadRequest(UUID, UUID, UUID)} */
  @Deprecated()
  public static Region get(UUID regionUUID) {
    return find.query().fetch("provider").where().idEq(regionUUID).findOne();
  }

  public static Region getByCode(Provider provider, String code) {
    return find.query().where().eq("provider_uuid", provider.uuid).eq("code", code).findOne();
  }

  public static List<Region> getByProvider(UUID providerUUID) {
    return find.query().where().eq("provider_uuid", providerUUID).findList();
  }

  public static Region getOrBadRequest(UUID customerUUID, UUID providerUUID, UUID regionUUID) {
    Region region = get(customerUUID, providerUUID, regionUUID);
    if (region == null) {
      throw new YWServiceException(BAD_REQUEST, "Invalid Provider/Region UUID");
    }
    return region;
  }

  /** DEPRECATED: use {@link #getOrBadRequest(UUID, UUID, UUID)} */
  @Deprecated
  public static Region get(UUID customerUUID, UUID providerUUID, UUID regionUUID) {
    String regionQuery =
        " select r.uuid, r.code, r.name"
            + "   from region r join provider p on p.uuid = r.provider_uuid "
            + "  where r.uuid = :r_uuid and p.uuid = :p_uuid and p.customer_uuid = :c_uuid";

    RawSql rawSql = RawSqlBuilder.parse(regionQuery).create();
    Query<Region> query = Ebean.find(Region.class);
    query.setRawSql(rawSql);
    query.setParameter("r_uuid", regionUUID);
    query.setParameter("p_uuid", providerUUID);
    query.setParameter("c_uuid", customerUUID);
    return query.findOne();
  }

  /**
   * Fetch Regions with the minimum zone count and having a valid yb server image.
   *
   * @param customerUUID
   * @param providerUUID
   * @param minZoneCount
   * @return List of PlacementRegion
   */
  public static List<Region> fetchValidRegions(
      UUID customerUUID, UUID providerUUID, int minZoneCount) {
    String regionQuery =
        " select r.uuid, r.code, r.name"
            + "   from region r join provider p on p.uuid = r.provider_uuid "
            + "   left outer join availability_zone zone on zone.region_uuid = r.uuid "
            + "  where p.uuid = :p_uuid and p.customer_uuid = :c_uuid"
            + "  group by r.uuid "
            + " having count(zone.uuid) >= "
            + minZoneCount;

    RawSql rawSql = RawSqlBuilder.parse(regionQuery).create();
    Query<Region> query = Ebean.find(Region.class);
    query.setRawSql(rawSql);
    query.setParameter("p_uuid", providerUUID);
    query.setParameter("c_uuid", customerUUID);
    return query.findList();
  }

  public void disableRegionAndZones() {
    beginTransaction();
    try {
      setActiveFlag(false);
      update();
      String s =
          "UPDATE availability_zone set active = :active_flag where region_uuid = :region_uuid";
      SqlUpdate updateStmt = Ebean.createSqlUpdate(s);
      updateStmt.setParameter("active_flag", false);
      updateStmt.setParameter("region_uuid", uuid);
      Ebean.execute(updateStmt);
      commitTransaction();
    } catch (Exception e) {
      throw new RuntimeException("Unable to flag Region UUID as deleted: " + uuid);
    } finally {
      endTransaction();
    }
  }

  public String toString() {
    return Json.newObject()
        .put("code", code)
        .put("provider", provider.uuid.toString())
        .put("name", name)
        .put("ybImage", ybImage)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .toString();
  }
}
