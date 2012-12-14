/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */
package net.sourceforge.pmd.testframework;

import java.io.IOException;
import java.io.InputStream;

import net.sourceforge.pmd.util.IOUtil;

public class StreamUtil {

	public static String toString(InputStream in) {
		if (in == null) {
			throw new NullPointerException("no input stream given");
		}

		StringBuilder sb = new StringBuilder();
		int c;
		try {
			while ((c = in.read()) != -1) {
				sb.append((char) c);
			}
		} catch (IOException e) {
			// ignored
		} finally {
			IOUtil.closeQuietly(in);
		}
		return sb.toString();
	}

}
