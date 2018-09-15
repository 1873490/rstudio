/*
 * TermBufferParser.hpp
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

#ifndef TERM_BUFFER_PARSER_HPP
#define TERM_BUFFER_PARSER_HPP

#include <string>

namespace rstudio {
namespace core {
namespace text {

// Remove all text between start/end alt-buffer escape sequences (vt100/xterm).
//
// The alt-buffer is used by full-screen programs such as vim. The terminal
// preserves and hides the normal scrolling buffer, then shows a non-scrolling
// buffer that fills the available screen. Upon leaving this mode, the
// alt-buffer is destroyed and the regular buffer is redisplayed.
//
// Allows alt-buffer regions to span calls to this function, via
// the in/out pAltModeActive parameter. That is, you could have an alt-start
// escape sequence in the buffer passed in one call, with no alt-end, in which
// case pAltModeActive would be set to true on exit of the function, and if you
// pass in true, then it is assumed the start of the buffer is already in alt-mode.
//
std::string stripSecondaryBuffer(
      const std::string& str, // string to parse
      bool* pAltModeActive); // (optional in/out) is string "in" alt-buffer mode?
      
// Remove any rsrun ESC sequences from strInput and return the string without them.
// If multiple ESC sequences are found, all are removed but only the contents of 
// the final one is returned in pRSRunESC (which will be set to an empty string
// if none are found).
std::string rsrunStripESC(const std::string& strInput, std::string *pRSRunESC);

} // namespace text
} // namespace core
} // namespace rstudio

#endif // TERM_BUFFER_PARSER_HPP
