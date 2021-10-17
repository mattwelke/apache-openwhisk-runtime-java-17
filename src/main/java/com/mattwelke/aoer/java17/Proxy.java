package com.mattwelke.aoer.java17;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.apache.openwhisk.runtime.java.action.WhiskSecurityManager;

/**
 * Implements the OpenWhisk proxy contact to create a runtime.
 * Based on https://github.com/mattwelke/openwhisk-runtime-java/blob/main/core/java8/proxy/src/main/java/org/apache/openwhisk/runtime/java/action/Proxy.java
 */
public class Proxy {

    private HttpServer server;

    private JarLoader loader = null;

    public Proxy(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), -1);

        this.server.createContext("/init", new InitHandler());
        this.server.createContext("/run", new RunHandler());
        this.server.setExecutor(null); // creates a default executor
    }

    public void start() {
        server.start();
    }

    private static void writeResponse(HttpExchange t, int code, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(code, bytes.length);
        OutputStream os = t.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static void writeError(HttpExchange t, String errorMessage) throws IOException {
        JsonObject message = new JsonObject();
        message.addProperty("error", errorMessage);
        writeResponse(t, 502, message.toString());
    }

    private static void writeLogMarkers() {
        System.out.println("XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX");
        System.err.println("XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX");
        System.out.flush();
        System.err.flush();
    }

    private class InitHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (loader != null) {
                String errorMessage = "Cannot initialize the action more than once.";
                System.err.println(errorMessage);
                Proxy.writeError(t, errorMessage);
                return;
            }

            try {
                InputStream is = t.getRequestBody();
                // TODO: Rewrite code using deprecated Gson methods
                JsonParser parser = new JsonParser();
                JsonElement ie = parser.parse(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)));
                JsonObject inputObject = ie.getAsJsonObject();

                if (inputObject.has("value")) {
                    JsonObject message = inputObject.getAsJsonObject("value");
                    if (message.has("main") && message.has("code")) {
                        String mainClass = message.getAsJsonPrimitive("main").getAsString();
                        String base64Jar = message.getAsJsonPrimitive("code").getAsString();

                        // FIXME: this is obviously not very useful. The idea is that we
                        // will implement/use a streaming parser for the incoming JSON object so that we
                        // can stream the contents of the jar straight to a file.
                        InputStream jarIs = new ByteArrayInputStream(base64Jar.getBytes(StandardCharsets.UTF_8));

                        // Save the bytes to a file.
                        Path jarPath = JarLoader.saveBase64EncodedFile(jarIs);

                        // Start up the custom classloader. This also checks that the
                        // main method exists.
                        loader = new JarLoader(jarPath, mainClass);

                        Proxy.writeResponse(t, 200, "OK");
                        return;
                    }
                }

                Proxy.writeError(t, "Missing main/no code to execute.");
                return;
            } catch (Exception e) {
                e.printStackTrace(System.err);
                writeLogMarkers();
                Proxy.writeError(t, "An error has occurred (see logs for details): " + e);
                return;
            }
        }
    }

    private class RunHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (loader == null) {
                Proxy.writeError(t, "Cannot invoke an uninitialized action.");
                return;
            }

            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            SecurityManager sm = System.getSecurityManager();

            try {
                var reader = new BufferedReader(new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8));
                Map<String, Object> body = new Gson().fromJson(reader, Map.class);

                // Use the "value" property as the input object, which will be the first map passed to the user code.
                Map<String, Object> value = (Map<String, Object>) body.get("value");

                // Remove "value" so that the map can be repurposed as the OpenWhisk variables map which will be the
                // second map passed to the user code.
                body.remove("value");
                Map<String, Object> owVars = body;

                Thread.currentThread().setContextClassLoader(loader);
                System.setSecurityManager(new WhiskSecurityManager());

                // User code starts running here.
                Map<String, Object> output = loader.invokeMain(value, owVars);
                // User code finished running here.

                if (output == null) {
                    throw new NullPointerException("The action returned null");
                }

                Proxy.writeResponse(t, 200, new Gson().toJson(output));
            } catch (InvocationTargetException ite) {
                // These are exceptions from the action, wrapped in ite because
                // of reflection
                Throwable underlying = ite.getCause();
                underlying.printStackTrace(System.err);
                Proxy.writeError(t,
                        "An error has occurred while invoking the action (see logs for details): " + underlying);
            } catch (Exception e) {
                e.printStackTrace(System.err);
                Proxy.writeError(t, "An error has occurred (see logs for details): " + e);
            } finally {
                writeLogMarkers();
                System.setSecurityManager(sm);
                Thread.currentThread().setContextClassLoader(cl);
            }
        }
    }

    /**
     * Custom JAR loader.
     * Based on https://github.com/apache/openwhisk-runtime-java/blob/master/core/java8/proxy/src/main/java/org/apache/openwhisk/runtime/java/action/JarLoader.java
     */
    private static class JarLoader extends URLClassLoader {
        private final Method mainMethod;

        public static Path saveBase64EncodedFile(InputStream encoded) throws Exception {
            Base64.Decoder decoder = Base64.getDecoder();

            InputStream decoded = decoder.wrap(encoded);

            File destinationFile = File.createTempFile("useraction", ".jar");
            destinationFile.deleteOnExit();
            Path destinationPath = destinationFile.toPath();

            Files.copy(decoded, destinationPath, StandardCopyOption.REPLACE_EXISTING);

            return destinationPath;
        }

        public JarLoader(Path jarPath, String entrypoint)
                throws MalformedURLException, ClassNotFoundException, NoSuchMethodException, SecurityException {
            super(new URL[] { jarPath.toUri().toURL() });

            final String[] splitEntrypoint = entrypoint.split("#");
            final String entrypointClassName = splitEntrypoint[0];
            final String entrypointMethodName = splitEntrypoint.length > 1 ? splitEntrypoint[1] : "main";

            Class<?> mainClass = loadClass(entrypointClassName);

            Method m = mainClass.getMethod(entrypointMethodName, new Class[] { Map.class, Map.class });
            m.setAccessible(true);
            int modifiers = m.getModifiers();
            if (m.getReturnType() != Map.class || !Modifier.isStatic(modifiers) || !Modifier.isPublic(modifiers)) {
                throw new NoSuchMethodException("main");
            }
            this.mainMethod = m;
        }

        public Map<String, Object> invokeMain(Map<String, Object> value, Map<String, Object> owVars) throws Exception {
            return (Map<String, Object>) mainMethod.invoke(null, value, owVars);
        }
    }

    public static void main(String[] args) throws Exception {
        Proxy proxy = new Proxy(8080);
        proxy.start();
    }
}
