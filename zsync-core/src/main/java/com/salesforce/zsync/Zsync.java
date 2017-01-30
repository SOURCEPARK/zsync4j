/**
 * Copyright (c) 2015, Salesforce.com, Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * Neither the name of Salesforce.com nor the names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.zsync;

import static com.salesforce.zsync.internal.util.HttpClient.newHttpClient;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.salesforce.zsync.ZsyncStatsObserver.ZsyncStats;
import com.salesforce.zsync.http.Credentials;
import com.salesforce.zsync.internal.BlockMatcher;
import com.salesforce.zsync.internal.ChecksumValidationIOException;
import com.salesforce.zsync.internal.ControlFile;
import com.salesforce.zsync.internal.EventDispatcher;
import com.salesforce.zsync.internal.Header;
import com.salesforce.zsync.internal.OutputFileWriter;
import com.salesforce.zsync.internal.util.HttpClient;
import com.salesforce.zsync.internal.util.ObservableInputStream;
import com.salesforce.zsync.internal.util.RollingBuffer;
import com.salesforce.zsync.internal.util.ZeroPaddedReadableByteChannel;
import com.salesforce.zsync.internal.util.ZsyncUtil;
import com.salesforce.zsync.internal.util.HttpClient.HttpError;
import com.salesforce.zsync.internal.util.HttpClient.HttpTransferListener;
import com.salesforce.zsync.internal.util.ObservableRedableByteChannel.ObservableReadableResourceChannel;
import com.salesforce.zsync.internal.util.TransferListener.ResourceTransferListener;
import com.squareup.okhttp.OkHttpClient;

/**
 * Zsync download client: reduces the number of bytes retrieved from a remote
 * server by drawing unchanged parts of the file from a set of local input
 * files.
 *
 * @see <a href="http://zsync.moria.org.uk/">http://zsync.moria.org.uk/</a>
 *
 * @author bbusjaeger
 *
 */
public class Zsync {

  /**
   * Optional arguments to the zsync client.
   *
   * @see <a href="http://linux.die.net/man/1/zsync">zsync(1) - Linux man
   *      page</a>
   *
   * @author bbusjaeger
   *
   */
  public static class Options {

    private final List<Path> inputFiles = new ArrayList<>(2);
    private Path outputFile;
    private Path saveZsyncFile;
    private URI zsyncUri;
    private final Map<String, Credentials> credentials = new HashMap<>(2);
    private boolean quiet;

    public Options() {
      super();
    }

    public Options(Options other) {
      if (other != null) {
        this.inputFiles.addAll(other.getInputFiles());
        this.outputFile = other.outputFile;
        this.saveZsyncFile = other.saveZsyncFile;
        this.zsyncUri = other.zsyncUri;
        this.credentials.putAll(other.credentials);
        this.quiet = other.quiet;
      }
    }

    /**
     * Corresponds to zsync -i parameter: location of an input file from
     * which to directly copy matching blocks to the output file to reduce
     * the number of bytes that have to be fetched from the remote source.
     *
     * @param inputFile
     * @return
     */
    public Options addInputFile(Path inputFile) {
      this.inputFiles.add(inputFile);
      return this;
    }

    /**
     * Input files to construct output file from. If empty and the output
     * file does not yet exist, the full content is retrieved from the
     * remote location.
     *
     * @return
     */
    public List<Path> getInputFiles() {
      return this.inputFiles;
    }

    /**
     * Corresponds to zsync -o parameter: the location at which to store the
     * output file.
     *
     * @param outputFile
     * @return
     */
    public Options setOutputFile(Path outputFile) {
      this.outputFile = outputFile;
      return this;
    }

    /**
     * Location at which to store the output file. If not set, output will
     * be stored in the current working directory using the
     * <code>Filename</code> header from the control file as the relative
     * path. If the output file already exists, it will also be used as an
     * input file to reuse matching blocks. Upon completion of the zsync
     * operation, the output file is atomically replaced with the new
     * content (with fall-back to non-atomic in case the file system does
     * not support it).
     *
     * @return
     */
    public Path getOutputFile() {
      return this.outputFile;
    }

    /**
     * Corresponds to the zsync -k parameter: the location at which to store
     * the zsync control file. This option only takes effect if the zsync
     * URI passed as the first argument to {@link Zsync#zsync(URI, Options)}
     * is a remote (http) URL.
     *
     * @param saveZsyncFile
     * @return
     */
    public Options setSaveZsyncFile(Path saveZsyncFile) {
      this.saveZsyncFile = saveZsyncFile;
      return this;
    }

    /**
     * The location at which to persist the zsync file if remote, may be
     * null
     *
     * @return
     */
    public Path getSaveZsyncFile() {
      return this.saveZsyncFile;
    }

    /**
     * Corresponds to the zsync -u parameter: the source URI from which the
     * zsync file was originally retrieved. Takes affect only if the first
     * parameter to the {@link Zsync#zsync(URI, Options)} method refers to a
     * local file.
     *
     * @param zsyncUri
     * @return
     */
    public Options setZsyncFileSource(URI zsyncUri) {
      this.zsyncUri = zsyncUri;
      return this;
    }

    /**
     * The remote URI from which the local zsync file was originally
     * retrieved from
     *
     * @return
     */
    public URI getZsyncFileSource() {
      return this.zsyncUri;
    }

    /**
     * Registers the given credentials for the given host name. A
     * {@link Zsync} instance applies the credentials as follows:
     * <ol>
     * <li>The first request issued to a given host is sent without any form
     * of authentication information to give the server the opportunity to
     * challenge the request.</li>
     * <li>If a 401 Basic authentication challenge is received and
     * credentials for the given host are specified in the options, the
     * request is retried with a basic Authorization header.</li>
     * <li>Subsequent https requests to the same host are sent with a basic
     * Authorization header in the first request. This challenge caching is
     * per host an does not take realms received as part of challenge
     * responses into account. Challenge caching is disabled for http
     * requests to give the server an opportunity to redirect to https.</li>
     * </ol>
     *
     * @param hostname
     * @param credentials
     * @return
     */
    public Options putCredentials(String hostname, Credentials credentials) {
      this.credentials.put(hostname, credentials);
      return this;
    }

    /**
     * Registered credentials
     *
     * @return
     */
    public Map<String, Credentials> getCredentials() {
      return this.credentials;
    }

    public void setQuiet(boolean b) {
      this.quiet = b;
    }

    public boolean getQuiet() {
      return this.quiet;
    }

  }

  public static final String VERSION = "0.6.3";

  private final HttpClient httpClient;

  /**
   * Creates a new zsync client
   */
  public Zsync() {
    this(newHttpClient());
  }

  /**
   * Creates a new zsync client that reuses a clone of the given http client.
   *
   * @param okHttpClient
   */
  public Zsync(OkHttpClient okHttpClient) {
    this(newHttpClient(okHttpClient));
  }

  /* currently internal as HttpClient not exposed */
  private Zsync(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  /**
   * Convenience method for {@link #zsync(URI, Options, ZsyncObserver)}
   * without options or observer. The URI passed to this method must be an
   * absolute HTTP URL. The output file location will be derived from the
   * filename header in the zsync control file as described in
   * {@link Options#getOutputFile(Path)}. If the output file already exists,
   * zsync will reuse unchanged blocks, download only changed blocks, and
   * replace the output file upon successful completion. Otherwise, zsync will
   * download the full content from the remote server.
   *
   * @param zsyncFile
   *            Absolute HTTP URL of the zsync control file generated by
   *            {@link ZsyncMake}
   * @return Path location of the written output file
   * @throws ZsyncException
   *             if an unexpected error occurs
   */
  public Path zsync(URI zsyncFile) throws ZsyncException {
    return this.zsync(zsyncFile, null);
  }

  /**
   * Convenience method for {@link #zsync(URI, Options)} without observer.
   *
   * @param zsyncFile
   *            URI of the zsync control file generated for the target file by
   *            {@link ZsyncMake}
   * @param options
   *            Optional parameters to the zsync operation
   * @return Path location of the written output file
   * @throws ZsyncException
   *             if an unexpected error occurs
   */
  public Path zsync(URI zsyncFile, Options options) throws ZsyncException {
    return this.zsync(zsyncFile, options, null);
  }

  /**
   * Runs zsync to delta-download an http file per
   * {@see http://zsync.moria.org.uk/}.
   *
   * <p>
   * The zsyncFile parameter must point to the zsync control file generated by
   * {@link ZsyncMake}. Typically, this will be an absolute HTTP URI, but it
   * can also be an absolute or relative file system URI in case the file has
   * already been downloaded. The URI of the actual file to download is
   * determined from the <code>URI</code> header value in the zsync control
   * file. If that value is a relative URI, it is resolved against the remote
   * zsync file URI. For example, if the control file is located at
   * <code>http://myhost.com/file.zsync</code> and the header value is
   * <code>file</code>, then the resolved URI is
   * <code>http://myhost.com/file</code>. Note that if the zsyncFile parameter
   * is a local file system URI and the URI header value is a relative URI,
   * the {@link Options#getZsyncFileSource()} option must be set to resolve
   * the absolute URI.
   * </p>
   * <p>
   * The options parameter is optional, i.e. it may be null or empty. It can
   * be used to pass optional parameters to zsync per the documentation on the
   * get and set methods on the {@link Options} class. For example, additional
   * input files can be specified via {@link Options#addInputFile(Path)}; the
   * output location can be set via {@link Options#setOutputFile(Path)}.
   * </p>
   * <p>
   * The observer parameter is also optional. Passing a {@link ZsyncObserver}
   * observer enables fine-grained progress and statistics reporting. For the
   * latter {@link ZsyncStatsObserver} can be used directly.
   * </p>
   *
   * @param zsyncFile
   *            URI of the zsync control file generated for the target file by
   *            {@link ZsyncMake}
   * @param options
   * @param observer
   * @return
   * @throws ZsyncException
   */
  public Path zsync(URI zsyncFile, Options options, ZsyncObserver observer) throws ZsyncException {
    final EventDispatcher events = new EventDispatcher(observer == null ? new ZsyncObserver() : observer);
    try {
      options = new Options(options); // Copy, since the supplied Options
      // object is mutable
      events.zsyncStarted(zsyncFile, options);
      return zsyncInternal(zsyncFile, options, events);
    } catch (ZsyncException | RuntimeException exception) {
      events.zsyncFailed(exception);
      throw exception;
    } finally {
      events.zsyncComplete();
    }
  }

  private Path zsyncInternal(URI zsyncFile, Options options, EventDispatcher events) throws ZsyncException {
    final ControlFile controlFile;
    try (InputStream in = this.openZsyncFile(zsyncFile, this.httpClient, options, events)) {
      controlFile = ControlFile.read(in);
    } catch (final HttpError e) {
      if (e.getCode() == HTTP_NOT_FOUND) {
        throw new ZsyncControlFileNotFoundException("Zsync file " + zsyncFile + " does not exist.", e);
      }
      throw new ZsyncException("Unexpected Http error retrieving zsync file", e);
    } catch (final NoSuchFileException e) {
      throw new ZsyncControlFileNotFoundException("Zsync file " + zsyncFile + " does not exist.", e);
    } catch (final IOException e) {
      throw new ZsyncException("Failed to read zsync control file", e);
    }

    // determine output file location
    Path outputFile = options.getOutputFile();
    if (outputFile == null) {
      outputFile = Paths.get(controlFile.getHeader().getFilename());
    }

    // use the output file as a seed if it already exists
    if (Files.exists(outputFile)) {
      options.getInputFiles().add(outputFile);
    }

    // determine remote file location
    URI remoteFileUri = URI.create(controlFile.getHeader().getUrl());
    if (!remoteFileUri.isAbsolute()) {
      if (options.getZsyncFileSource() == null) {
        throw new IllegalArgumentException("Remote file path is relative, but no zsync file source URI set to resolve it");
      }
      remoteFileUri = options.getZsyncFileSource().resolve(remoteFileUri);
    }

    try (final OutputFileWriter outputFileWriter = new OutputFileWriter(outputFile, controlFile, events.getOutputFileWriteListener())) {
      if (!processInputFiles(outputFileWriter, controlFile, options.getInputFiles(), events)) {
        this.httpClient.partialGet(remoteFileUri, outputFileWriter.getMissingRanges(), options.getCredentials(), events.getRangeReceiverListener(outputFileWriter),
          //The OkHttpClient gives misleading errors so the HttpsURLConnection is useful for debugging
          //this.httpClient.partialGetOracle(remoteFileUri, outputFileWriter.getMissingRanges(), options.getCredentials(), events.getRangeReceiverListener(outputFileWriter),
        events.getRemoteFileDownloadListener());
      }
    } catch (final ConnectException exception) {
      throw new ZsyncException("Did you install UnlimitedJCEPolicyJDK8?", exception);
    } catch (final ChecksumValidationIOException exception) {
      throw new ZsyncChecksumValidationFailedException("Calculated checksum does not match expected checksum");
    } catch (IOException | HttpError e) {
      throw new ZsyncException(e);
    }

    return outputFile;
  }

  /**
   * Opens the zsync file referred to by the given URI for read. If the file
   * refers to a local file system path, the local file is opened directly.
   * Otherwise, if the file is remote and {@link Options#getSaveZsyncFile()}
   * is specified, the remote file is stored locally in the given location
   * first and then opened for read locally. If the file is remote and no save
   * location is specified, the file is opened for read over the remote
   * connection.
   * <p>
   * If the file is remote, the method always calls
   * {@link Options#setZsyncFileSource(URI)} on the passed in options
   * parameter, so that relative file URLs in the control file can later be
   * resolved against it.
   *
   * @param zsyncFile
   * @param httpClient
   * @param options
   *
   * @return
   * @throws IOException
   * @throws HttpError
   */
  private InputStream openZsyncFile(URI zsyncFile, HttpClient httpClient, Options options, EventDispatcher events) throws IOException, HttpError {
    final InputStream in;
    if (zsyncFile.isAbsolute()) {
      // check if it's a local URI
      final Path path = ZsyncUtil.getPath(zsyncFile);
      if (path == null) {
        // TODO we may want to set the redirect URL resulting from
        // processing the http request
        options.setZsyncFileSource(zsyncFile);
        final HttpTransferListener listener = events.getControlFileDownloadListener();
        final Map<String, Credentials> credentials = options.getCredentials();
        // check if we should persist the file locally
        final Path savePath = options.getSaveZsyncFile();
        if (savePath == null) {
          in = httpClient.get(zsyncFile, credentials, listener);
        } else {
          httpClient.get(zsyncFile, savePath, credentials, listener);
          in = this.openZsyncFile(savePath, events);
        }
      } else {
        in = this.openZsyncFile(path, events);
      }
    } else {
      final String path = zsyncFile.getPath();
      if (path == null) {
        throw new IllegalArgumentException("Invalid zsync file URI: path of relative URI missing");
      }
      in = this.openZsyncFile(Paths.get(path), events);
    }
    return in;
  }

  private InputStream openZsyncFile(Path zsyncFile, EventDispatcher events) throws IOException {
    return new ObservableInputStream(Files.newInputStream(zsyncFile), events.getControlFileReadListener());
  }

  private boolean processInputFiles(OutputFileWriter targetFile, ControlFile controlFile, Iterable<? extends Path> inputFiles, EventDispatcher events) throws IOException {
    for (final Path inputFile : inputFiles) {
      if (processInputFile(targetFile, controlFile, inputFile, events.getInputFileReadListener())) {
        return true;
      }
    }
    return false;
  }

  private boolean processInputFile(OutputFileWriter targetFile, ControlFile controlFile, Path inputFile, ResourceTransferListener<Path> listener) throws IOException {
    final long size;
    try (final FileChannel fileChannel = FileChannel.open(inputFile);
    final ReadableByteChannel channel = new ObservableReadableResourceChannel<>(fileChannel, listener, inputFile, size = fileChannel.size())) {
      final BlockMatcher matcher = BlockMatcher.create(controlFile);
      final int matcherBlockSize = matcher.getMatcherBlockSize();
      final ReadableByteChannel c = zeroPad(channel, size, matcherBlockSize, controlFile.getHeader());
      final RollingBuffer buffer = new RollingBuffer(c, matcherBlockSize, 16 * matcherBlockSize);
      int bytes;
      do {
        bytes = matcher.match(targetFile, buffer);
      } while (buffer.advance(bytes));
    }
    return targetFile.isComplete();
  }

  /**
   * Pads the given channel with zeros if the length of the input file is not
   * evenly divisible by the block size. The is necessary to match how the
   * checksums in the zsync file are computed.
   *
   * @param channel
   *            channel for input file to pad
   * @param header
   *            header of the zsync file being processed.
   * @return
   * @throws IOException
   */
  static ReadableByteChannel zeroPad(ReadableByteChannel channel, long size, int matcherBlockSize, Header header) throws IOException {
    final int numZeros;
    if (size < matcherBlockSize) {
      numZeros = matcherBlockSize - (int) size;
    } else {
      final int blockSize = header.getBlocksize();
      final int lastBlockSize = (int) (size % blockSize);
      numZeros = lastBlockSize == 0 ? 0 : blockSize - lastBlockSize;
    }
    return numZeros == 0 ? channel : new ZeroPaddedReadableByteChannel(channel, numZeros);
  }

  // this is just a temporary hacked up CLI for testing purposes
  public static void main(String[] args) throws IOException, ZsyncException {
    if (args.length == 1 && "-h".equals(args[0])) {
      help();
      System.exit(0);
    }
    if (args.length == 1 && "-V".equals(args[0])) {
      version();
      System.exit(0);
    }
    final FileSystem fs = FileSystems.getDefault();
    final Options options = new Options();
    final ArrayList<String> list = new ArrayList<String>(Arrays.asList(args));
    int index = list.indexOf("-i");
    if (index > -1) {
      list.remove(index);
      final String value = list.remove(index);
      options.addInputFile(fs.getPath(value));
    }
    index = list.indexOf("-o");
    if (index > -1) {
      list.remove(index);
      final String value = list.remove(index);
      options.setOutputFile(fs.getPath(value));
    }
    index = list.indexOf("-u");
    if (index > -1) {
      list.remove(index);
      final String value = list.remove(index);
      final URI uri = URI.create(value);
      options.setZsyncFileSource(uri);
    }
    index = list.indexOf("-s");
    if (index > -1) {
      list.remove(index);
      options.setQuiet(true);
    }
    index = list.indexOf("-q");
    if (index > -1) {
      list.remove(index);
      options.setQuiet(true);
    }
    index = list.indexOf("-A");
    if (index > -1) {
      list.remove(index);
      final String value = list.remove(index);
      final int eq, cl;
      if ((eq = value.indexOf('=')) > 0 && (cl = value.indexOf(':', eq + 1)) > 0) {
        options.putCredentials(value.substring(0, eq), new Credentials(value.substring(eq + 1, cl), value.substring(cl + 1)));
      } else {
        error("authenticator must be of form 'hostname=username:password'");
      }
    }
    if (list.size() < 1) {
      error("No .zsync file URL specified");
    }
    if (list.size() > 1) {
      error("Illegal option " + list.get(0));
    }
    final String file = list.remove(0);
    final URI uri = URI.create(file);
    final Zsync zsync = new Zsync(new OkHttpClient());
    final ZsyncStatsObserver observer = new ZsyncStatsObserver();
    zsync.zsync(uri, options, observer);
    final ZsyncStats stats = observer.build();
    if (!options.getQuiet()) {
      System.out.println(
      "Downloaded " + friendlySize(stats.getTotalBytesDownloaded()) + " of " + friendlySize(stats.getTotalBytesWritten()) + " in " + friendlyTime(stats.getElapsedMillisecondsDownloading()) + ".");
    }
  }

  public static String friendlySize(long size) {
    if (size < 0) {
      return "error";
    } else if (size == 0) {
      return "0";
    }
    final String[] units = new String[] { "B", "KB", "MB", "GB", "TB" };
    final int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
    return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
  }

  public static String friendlyTime(long time) {
    if (time < 0) {
      return "error";
    } else if (time == 0) {
      return "no time";
    }
    if (time < 1000) {
      return time + "ms";
    }
    final DecimalFormat d = new DecimalFormat("#,##0.#");
    if (time < 1000 * 60) {
      return d.format(time / 1000) + "s";
    }
    if (time < 1000 * 60 * 60) {
      return d.format(time / (1000 * 60)) + "m";
    }
    if (time < 1000 * 60 * 60 * 24) {
      return d.format(time / (1000 * 60 * 60)) + "h";
    }
    return d.format(time / (1000 * 60 * 60 * 24)) + "d";
  }

  private static void error(String err) {
    System.err.println(err);
    System.err.println("Usage: java -jar zsync.jar -h");
    System.exit(1);
  }

  private static void version() {
    System.out.println("zsync4j version " + VERSION + ". Copyright (c) 2015, Salesforce.com, Inc.");
  }

  static void help() {
    System.out.println("NAME\n"
      + "      zsync4j - Partial/differential file download client over HTTPS for Java\n"
      + "\n"
      + "SYNTAX\n"
      + "      java -jar zsync.jar [ -u url ] [ -i inputfile ] [ -o outputfile ] [ { -s | -q } ] [ -A hostname=username:password ] { filename | url }\n"
      + "\n"
      + "      java -jar zsync.jar -V\n"
      + "\n"
      + "DESCRIPTION\n"
      + "      Downloads  a file over HTTPS. zsync uses a control file to determine whether any blocks in the file are already known to the downloader, and only downloads\n"
      + "      the new blocks.\n"
      + "\n"
      + "      Either a filename or a URL can be given on the command line - this is the path of the control file for the download, which normally has the  name  of  the\n"
      + "      actual  file to downlaod with .zsync appended. (To create this .zsync file you have to have a copy of the target file, so this file should be generated by\n"
      + "      the person providing the download).\n"
      + "\n"
      + "      zsync downloads to your current directory. It looks for any file in the directory of the same name as the file to download. If it finds  one,  it  assumes\n"
      + "      that  this  is  an earlier or incomplete version of the new file to download, and scans this file for any blocks that it can use to build the target file.\n"
      + "      (It also looks for a file of the same name with .part appended, so it will automatically find previously interrupted zsync downloads and  reuse  the  data\n"
      + "      already downloaded. If you know that the local file to use as input has a different name, you must use -i)\n"
      + "\n"
      + "      zsync retrieves the rest of the target file over HTTP. Once the download is finished, the old version (if the new file wants the same name) is moved aside\n"
      + "      (a .zs-old extension is appended). The modification time of the file is set to be the same as the remote source file (if specified in the .zsync).\n"
      + "\n"
      + "OPTIONS\n"
      + "      -A hostname=username:password\n"
      + "             Specifies a username and password to be used with the given hostname. -A can be used multiple times (with different hostnames), in cases where e.g.\n"
      + "             the  download servers (there could be different auth details for different servers - and zsync never assumes that your password should be sent to a\n"
      + "             server other than the one named - otherwise redirects would be dangerous!).\n"
      + "\n"
      + "      -i inputfile\n"
      + "             Specifies (extra) input files. inputfile is scanned to identify blocks in common with the target file and zsync uses any blocks found. Can be  used\n"
      + "             multiple times.\n"
      + "\n"
      + "      -o outputfile\n"
      + "             Override the default output file name.\n"
      + "\n"
      + "      -q     Suppress the progress bar, download rate and ETA display.\n"
      + "\n"
      + "      -s     Deprecated synonym for -q.\n"
      + "\n"
      + "      -u url This specifies the referring URL.  If you have a .zsync file locally (if you downloaded it separately, with wget, say) and the .zsync file contains\n"
      + "             a  relative  URL, you need to specify where you got the .zsync file from so that zsync knows which server and path to use for the rest of the down-\n"
      + "             load (this is analogous to adding a <base href=\"...\"> to a downloaded web page to make the links work).\n"
      + "\n"
      + "      -V     Prints the version of zsync.\n"
      + "\n"
      + "JAVA OPTIONS\n"
      + "      -Djavax.net.debug=ssl:handshake\n"
      + "             Provides SSL debug information which will indicate if UnlimitedJCEPolicyJDK8 is installed or required\n"
      + "\n"
      + "SEE ALSO\n"
      + "      zsyncmake(1)\n"
      + "\n");
    version();
  }
}
