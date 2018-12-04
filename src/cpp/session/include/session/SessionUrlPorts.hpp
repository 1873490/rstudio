/*
 * SessionUrlPorts.hpp
 *
 * Copyright (C) 2009-18 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */


#ifndef SESSION_URL_PORTS_HPP
#define SESSION_URL_PORTS_HPP

#include <string>

namespace rstudio {
namespace session {
namespace url_ports {

void setPortToken(const std::string& token);

std::string portToken();

std::string mapUrlPorts(const std::string& url);

}  // namespace url_ports
}  // namespace session
}  // namespace rstudio

#endif
