/*
 * SessionProfiler.cpp
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
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


#include "SessionProfiler.hpp"

#include <core/Exec.hpp>

#include <session/SessionModuleContext.hpp>

#include <core/Error.hpp>
#include <core/Exec.hpp>

#include <r/RSexp.hpp>
#include <r/RRoutines.hpp>
#include <r/RUtil.hpp>
#include <r/ROptions.hpp>

using namespace rstudio::core;

namespace rstudio {
namespace session {
namespace modules { 
namespace profiler {

namespace {

#define kProfilesCacheDir "profiles-cache"
#define kProfilesUrlPath "profiles"

std::string profilesCacheDir() 
{
   return module_context::scopedScratchPath().childPath(kProfilesCacheDir)
      .absolutePath();
}

SEXP rs_profilesPath()
{
	r::sexp::Protect rProtect;
	return r::sexp::create(profilesCacheDir(), &rProtect);
}

} // anonymous namespace

void handleProfilerResReq(const http::Request& request,
                            http::Response* pResponse)
{
   std::string resourceName = http::util::pathAfterPrefix(request, "/" kProfilesUrlPath "/");

   core::FilePath profilesPath = core::FilePath(profilesCacheDir());
   core::FilePath profileResource = profilesPath.childPath(resourceName);

   pResponse->setCacheableFile(profileResource, request);
}

Error initialize()
{  
   ExecBlock initBlock ;
   
   initBlock.addFunctions()
      (boost::bind(module_context::sourceModuleRFile, "SessionProfiler.R"))
      (boost::bind(module_context::registerUriHandler, "/" kProfilesUrlPath "/", handleProfilerResReq));

   // register rs_profilesPath
   r::routines::registerCallMethod(
            "rs_profilesPath",
            (DL_FUNC) rs_profilesPath,
            0);

   return initBlock.execute();

}

} // namespace profiler
} // namespace modules
} // namesapce session
} // namespace rstudio

