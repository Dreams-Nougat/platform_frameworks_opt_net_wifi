/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "wifi_chip_service.h"

#include <android-base/logging.h>

#include "failure_reason_util.h"

namespace android {
namespace hardware {
namespace wifi {

using android::hardware::hidl_vec;

WifiChipService::WifiChipService(
    WifiHalService* hal_service, wifi_interface_handle interface_handle) :
    hal_service_(hal_service), interface_handle_(interface_handle) {}

void WifiChipService::Invalidate() {
  hal_service_ = nullptr;
  callbacks_.clear();
}

Return<void> WifiChipService::registerEventCallback(
    const sp<IWifiChipEventCallback>& callback) {
  if (!hal_service_) return Void();
  callbacks_.insert(callback);
  return Void();
}

Return<void> WifiChipService::getAvailableModes(getAvailableModes_cb cb) {
  if (!hal_service_) {
    cb(hidl_vec<ChipMode>());
    return Void();
  } else {
    // TODO add implementation
    return Void();
  }
}

Return<void> WifiChipService::configureChip(uint32_t /*mode_id*/) {
  if (!hal_service_) return Void();
  // TODO add implementation
  return Void();
}

Return<uint32_t> WifiChipService::getMode() {
  if (!hal_service_) return 0;
  // TODO add implementation
  return 0;
}

Return<void> WifiChipService::requestChipDebugInfo() {
  if (!hal_service_) return Void();

  IWifiChipEventCallback::ChipDebugInfo result;
  result.driverDescription = "<unknown>";
  result.firmwareDescription = "<unknown>";
  char buffer[256];

  // get driver version
  bzero(buffer, sizeof(buffer));
  wifi_error ret = hal_service_->hal_func_table_.wifi_get_driver_version(
      interface_handle_, buffer, sizeof(buffer));
  if (ret == WIFI_SUCCESS) {
    result.driverDescription = buffer;
  } else {
    LOG(WARNING) << "Failed to get driver version: "
                 << LegacyErrorToString(ret);
  }

  // get firmware version
  bzero(buffer, sizeof(buffer));
  ret = hal_service_->hal_func_table_.wifi_get_firmware_version(
      interface_handle_, buffer, sizeof(buffer));
  if (ret == WIFI_SUCCESS) {
    result.firmwareDescription = buffer;
  } else {
    LOG(WARNING) << "Failed to get firmware version: "
                 << LegacyErrorToString(ret);
  }

  // send callback
  for (auto& callback : callbacks_) {
    callback->onChipDebugInfoAvailable(result);
  }
  return Void();
}


}  // namespace wifi
}  // namespace hardware
}  // namespace android
