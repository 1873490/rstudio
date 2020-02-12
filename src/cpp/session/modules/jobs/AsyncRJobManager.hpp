/*
 * AsyncRJobManager.hpp
 *
 * Copyright (C) 2009-19 by RStudio, PBC
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

#ifndef SESSION_ASYNC_R_JOB_MANAGER_HPP
#define SESSION_ASYNC_R_JOB_MANAGER_HPP

#include <session/jobs/Job.hpp>
#include <session/SessionAsyncRProcess.hpp>

namespace rstudio {
namespace core {
   class Error;
}
}

namespace rstudio {
namespace session {
namespace modules { 
namespace jobs {

/**
 * AsyncRJob contains a job (for use with the Jobs tab) that represents a background R process, for
 * e.g. R scripts, Shiny applications, etc.
 */
class AsyncRJob : public async_r::AsyncRProcess
{
public:
   AsyncRJob(const std::string& name);
   AsyncRJob(const std::string& name,
             const std::string& path,
             const std::string& workingDirectory,
             const std::string& importEnv,
             const std::string& exportEnv);

   // The ID of the underlying job; only valid after registration
   std::string id();

   // Cancel the job (abort the R session)
   void cancel();

   // Register the job (create the underlying job)
   void registerJob();

   // Set a callback for the job's exit
   void setOnComplete(boost::function<void()> onComplete);

   // Override default AsyncRProcess methods
   virtual void onStdout(const std::string& output);
   virtual void onStderr(const std::string& output);
   virtual void onCompleted(int exitStatus);

protected:
   // Whether the R process is complete
   bool completed_;

   // Whether the user has cancelled the R process
   bool cancelled_;

   // The underlying job
   boost::shared_ptr<Job> job_;

   boost::function<void()> onComplete_;
   std::string name_;
   std::string path_;
   std::string workingDirectory_;
   std::string importEnv_;
   std::string exportEnv_;
};

// Registers an asynchronous R job and returns the ID (which can be used later to stop the job)
core::Error registerAsyncRJob(boost::shared_ptr<AsyncRJob> job,
      std::string *pId);

// Stops an asynchronous R job
core::Error stopAsyncRJob(const std::string& id);
 
} // namespace jobs
} // namespace modules
} // namespace session
} // namespace rstudio

#endif // SESSION_ASYNC_R_JOB_MANAGER_HPP

