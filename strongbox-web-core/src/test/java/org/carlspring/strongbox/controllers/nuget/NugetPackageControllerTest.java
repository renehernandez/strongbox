package org.carlspring.strongbox.controllers.nuget;

import org.carlspring.strongbox.artifact.generator.NugetPackageGenerator;
import org.carlspring.strongbox.configuration.ConfigurationManager;
import org.carlspring.strongbox.controllers.context.IntegrationTest;
import org.carlspring.strongbox.data.PropertyUtils;
import org.carlspring.strongbox.rest.common.NugetRestAssuredBaseTest;
import org.carlspring.strongbox.storage.repository.Repository;
import org.carlspring.strongbox.storage.repository.RepositoryPolicyEnum;

import javax.inject.Inject;
import javax.xml.bind.JAXBException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;

import io.restassured.module.mockmvc.config.RestAssuredMockMvcConfig;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import ru.aristar.jnuget.files.NugetFormatException;
import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.CoreMatchers.equalTo;

/**
 * @author Sergey Bespalov
 *
 */
@IntegrationTest
@RunWith(SpringJUnit4ClassRunner.class)
public class NugetPackageControllerTest extends NugetRestAssuredBaseTest
{

    private static final String API_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJTdHJvbmdib3giLCJqdGkiOiJ0SExSbWU4eFJOSnJjNXVXdTVkZDhRIiwic3ViIjoiYWRtaW4iLCJzZWN1cml0eS10b2tlbi1rZXkiOiJhZG1pbi1zZWNyZXQifQ.xRWxXt5yob5qcHjsvV1YsyfY3C-XFt9oKPABY0tYx88";

    private final static String STORAGE_ID = "storage-nuget-test";

    private static final String REPOSITORY_RELEASES_1 = "nuget-releases-1";

    @Inject
    private ConfigurationManager configurationManager;


    @BeforeClass
    public static void cleanUp()
        throws Exception
    {
        cleanUp(getRepositoriesToClean());
    }

    public static Set<Repository> getRepositoriesToClean()
    {
        Set<Repository> repositories = new LinkedHashSet<>();
        repositories.add(createRepositoryMock(STORAGE_ID, REPOSITORY_RELEASES_1));

        return repositories;
    }

    @Override
    public void init()
        throws Exception
    {
        super.init();

        RestAssuredMockMvcConfig config = RestAssuredMockMvcConfig.config();
        config.getLogConfig().enableLoggingOfRequestAndResponseIfValidationFails();
        given().config(config);

        createStorage(STORAGE_ID);

        Repository repository1 = new Repository(REPOSITORY_RELEASES_1);
        repository1.setPolicy(RepositoryPolicyEnum.RELEASE.getPolicy());
        repository1.setStorage(configurationManager.getConfiguration().getStorage(STORAGE_ID));
        repository1.setLayout("Nuget Hierarchical");
        repository1.setIndexingEnabled(false);

        createRepository(repository1);
    }

    @Override
    public void shutdown()
    {
        super.shutdown();
    }

    @Test
    public void testPackageDelete()
        throws Exception
    {
        String basedir = PropertyUtils.getHomeDirectory() + "/tmp";

        String packageId = "Org.Carlspring.Strongbox.Examples.Nuget.Mono.Delete";
        String packageVersion = "1.0.0";
        Path packageFile = generatePackageFile(basedir, packageId, packageVersion);
        byte[] packageContent = readPackageContent(packageFile);

        // Push
        createPushRequest(packageContent).when()
                                         .put(getContextBaseUrl() + "/storages/" + STORAGE_ID + "/" +
                                              REPOSITORY_RELEASES_1 + "/")
                                         .peek()
                                         .then()
                                         .statusCode(HttpStatus.CREATED.value());

        // Delete
        given().header("User-Agent", "NuGet/*")
               .header("X-NuGet-ApiKey", API_KEY)
               .when()
               .delete(getContextBaseUrl() + "/storages/" + STORAGE_ID + "/" + REPOSITORY_RELEASES_1 + "/" +
                       packageId + "/" + packageVersion)
               .peek()
               .then()
               .statusCode(HttpStatus.OK.value());
    }

    @Test
    public void testPackageCommonFlow()
        throws Exception
    {
        String basedir = PropertyUtils.getHomeDirectory() + "/tmp";

        String packageId = "Org.Carlspring.Strongbox.Examples.Nuget.Mono";
        String packageVersion = "1.0.0";
        Path packageFile = generatePackageFile(basedir, packageId, packageVersion);
        long packageSize = Files.size(packageFile);
        byte[] packageContent = readPackageContent(packageFile);

        // Push
        createPushRequest(packageContent)
               .when()
               .put(getContextBaseUrl() + "/storages/" + STORAGE_ID + "/" + REPOSITORY_RELEASES_1 + "/")
               .peek()
               .then()
               .statusCode(HttpStatus.CREATED.value());


        // We need to mute `System.out` here manually because response body logging hardcoded in current version of
        // RestAssured, and we can not change it using configuration (@see `RestAssuredResponseOptionsGroovyImpl.peek(...)`).
        PrintStream originalSysOut = muteSystemOutput();
        try
        {
            // Get
            given().header("User-Agent", "NuGet/*")
                   .when()
                   .get(getContextBaseUrl() + "/storages/" + STORAGE_ID + "/" + REPOSITORY_RELEASES_1 + "/download/" +
                        packageId + "/" + packageVersion)
                   .peek()
                   .then()
                   .statusCode(HttpStatus.OK.value())
                   .assertThat()
                   .header("Content-Length", equalTo(String.valueOf(packageSize)));
        }
        finally
        {
            System.setOut(originalSysOut);
        }
    }

    /**
     * Mute the system output to avoid malicious logging (binary content for example).
     *
     * @return
     */
    private PrintStream muteSystemOutput()
    {
        PrintStream original = System.out;
        System.setOut(new PrintStream(new OutputStream()
        {
            public void write(int b)
            {
                //DO NOTHING
            }
        }));

        return original;
    }

    @Test
    public void testPackageSearch()
        throws Exception
    {
        String basedir = PropertyUtils.getHomeDirectory() + "/tmp";

        String packageId = "Org.Carlspring.Strongbox.Nuget.Test.Search";
        String packageVersion = "1.0.0";
        byte[] packageContent = readPackageContent(generatePackageFile(basedir, packageId, packageVersion));

        // Push
        createPushRequest(packageContent).when()
                                         .put(getContextBaseUrl() + "/storages/" + STORAGE_ID + "/" +
                                              REPOSITORY_RELEASES_1 + "/")
                                         .peek()
                                         .then()
                                         .statusCode(HttpStatus.CREATED.value());

        // Count
        given().header("User-Agent", "NuGet/*")
               .when()
               .get(getContextBaseUrl() + "/storages/" + STORAGE_ID + "/" + REPOSITORY_RELEASES_1 +
                    String.format("/Search()/$count?$filter=%s&searchTerm=%s&targetFramework=",
                                  "IsLatestVersion", "Test"))
               .then()
               .statusCode(HttpStatus.OK.value())
               .and()
               .assertThat()
               .body(equalTo("1"));

        // Search
        given().header("User-Agent", "NuGet/*")
               .when()
               .get(getContextBaseUrl() + "/storages/" + STORAGE_ID + "/" + REPOSITORY_RELEASES_1 +
                    String.format("/Search()?$filter=%s&$skip=%s&$top=%s&searchTerm=%s&targetFramework=",
                                  "IsLatestVersion", 0, 30, "Test"))
               .then()
               .statusCode(HttpStatus.OK.value())
               .and()
               .assertThat()
               .body("feed.title", equalTo("Packages"))
               .and()
               .assertThat()
               .body("feed.entry[0].title", equalTo("Org.Carlspring.Strongbox.Nuget.Test.Search"));
    }

    public byte[] readPackageContent(Path packageFilePath)
        throws IOException
    {
        ByteArrayOutputStream contentStream = new ByteArrayOutputStream();

        MultipartEntityBuilder.create()
                              .addBinaryBody("package",
                                             Files.newInputStream(packageFilePath))
                              .setBoundary("---------------------------123qwe")
                              .build()
                              .writeTo(contentStream);
        contentStream.flush();

        byte[] packageContent = contentStream.toByteArray();

        return packageContent;
    }

    public Path generatePackageFile(String basedir,
                                    String packageId,
                                    String packageVersion)
        throws NugetFormatException,
               JAXBException,
               IOException,
               NoSuchAlgorithmException
    {
        String packageFileName = packageId + "." + packageVersion + ".nupkg";

        NugetPackageGenerator nugetPackageGenerator = new NugetPackageGenerator(basedir);
        nugetPackageGenerator.generateNugetPackage(packageId, packageVersion);

        Path packageFilePath = Paths.get(basedir).resolve(packageVersion).resolve(packageFileName);
        return packageFilePath;
    }

    public MockMvcRequestSpecification createPushRequest(byte[] packageContent)
    {
        return given().header("User-Agent", "NuGet/*")
                      .header("X-NuGet-ApiKey", API_KEY)
                      .header("Content-Type", "multipart/form-data; boundary=---------------------------123qwe")
                      .body(packageContent);
    }

}
