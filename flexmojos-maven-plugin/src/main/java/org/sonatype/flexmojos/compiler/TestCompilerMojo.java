/**
 *  Copyright 2008 Marvin Herman Froeder
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 *
 */
package org.sonatype.flexmojos.compiler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.sonatype.flexmojos.utilities.MavenUtils;

import flex2.tools.oem.Report;

/**
 * Goal to compile the Flex test sources.
 * 
 * @author Marvin Herman Froeder (velo.br@gmail.com)
 * @since 1.0
 * @goal test-compile
 * @requiresDependencyResolution
 * @phase test
 */
public class TestCompilerMojo
    extends ApplicationMojo
{

    /**
     * Set this to 'true' to bypass unit tests entirely. Its use is NOT RECOMMENDED, but quite convenient on occasion.
     * 
     * @parameter expression="${maven.test.skip}"
     */
    private boolean skipTests;

    /**
     * @parameter
     */
    private File testRunnerTemplate;

    /**
     * File to be tested. If not defined assumes Test*.as and *Test.as
     * 
     * @parameter
     */
    private String[] includeTestFiles;

    /**
     * Files to exclude from testing. If not defined, assumes no exclusions
     * 
     * @parameter
     */
    private String[] excludeTestFiles;

    private List<String> testClasses;

    private File testFolder;

    /**
     * Socket connect port for flex/java communication
     * 
     * @parameter default-value="13539"
     */
    private int testPort;

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        getLog().info(
                       "flexmojos " + MavenUtils.getFlexMojosVersion()
                           + " - GNU GPL License (NO WARRANTY) - See COPYRIGHT file" );

        testFolder = new File( build.getTestSourceDirectory() );

        if ( skipTests )
        {
            getLog().warn( "Skipping test phase." );
            return;
        }
        else if ( !testFolder.exists() )
        {
            getLog().warn( "Test folder not found" + testFolder );
            return;
        }

        setUp();

        if ( testClasses == null || testClasses.isEmpty() )
        {
            getLog().warn( "No test classes found for pattern: " + Arrays.toString( includeTestFiles ) );
        }
        else
        {
            run();
            tearDown();
        }
    }

    @Override
    public void setUp()
        throws MojoExecutionException, MojoFailureException
    {
        isSetProjectFile = false;
        linkReport = false;
        loadExterns = null;

        if ( includeTestFiles == null )
        {
            includeTestFiles = new String[] { "Test*.as", "*Test.as" };
        }

        File outputFolder = new File( build.getTestOutputDirectory() );
        if ( !outputFolder.exists() )
        {
            outputFolder.mkdirs();
        }

        testClasses = getTestClasses();

        File testSourceFile;
        try
        {
            testSourceFile = generateTester( testClasses );
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Unable to generate tester class.", e );
        }

        sourceFile = null;
        source = testSourceFile;

        super.setUp();
    }

    @SuppressWarnings( "unchecked" )
    private List<String> getTestClasses()
    {
        Collection<File> testFiles =
            FileUtils.listFiles( testFolder, new WildcardFileFilter( includeTestFiles ), DirectoryFileFilter.DIRECTORY );

        if ( excludeTestFiles != null && excludeTestFiles.length > 0 )
        {
            getLog().debug( "excludeTestFiles: " + Arrays.asList( excludeTestFiles ) );
            Collection<File> excludedTestFiles =
                FileUtils.listFiles( testFolder, new WildcardFileFilter( excludeTestFiles ),
                                     DirectoryFileFilter.DIRECTORY );
            testFiles.removeAll( excludedTestFiles );
        }

        List<String> testClasses = new ArrayList<String>();

        int trimPoint = testFolder.getAbsolutePath().length() + 1;

        for ( File testFile : testFiles )
        {
            String testClass = testFile.getAbsolutePath();
            int endPoint = testClass.lastIndexOf( '.' );
            testClass = testClass.substring( trimPoint, endPoint );
            testClass = testClass.replace( '/', '.' ); // Unix OS
            testClass = testClass.replace( '\\', '.' ); // Windows OS
            testClasses.add( testClass );
        }
        getLog().debug( "testClasses: " + testClasses );
        return testClasses;
    }

    private File generateTester( List<String> testClasses )
        throws Exception
    {
        // can't use velocity, got:
        // java.io.InvalidClassException:
        // org.apache.velocity.runtime.parser.node.ASTprocess; class invalid for
        // deserialization

        StringBuilder imports = new StringBuilder();

        for ( String testClass : testClasses )
        {
            imports.append( "import " );
            imports.append( testClass );
            imports.append( ";" );
            imports.append( '\n' );
        }

        StringBuilder classes = new StringBuilder();

        for ( String testClass : testClasses )
        {
            testClass = testClass.substring( testClass.lastIndexOf( '.' ) + 1 );
            classes.append( "addTest( " );
            classes.append( testClass );
            classes.append( ");" );
            classes.append( '\n' );
        }

        InputStream templateSource = getTemplate();
        String sourceString = IOUtils.toString( templateSource );
        sourceString = sourceString.replace( "$imports", imports );
        sourceString = sourceString.replace( "$testClasses", classes );
        sourceString = sourceString.replace( "$port", String.valueOf( testPort ) );
        File testSourceFile = new File( build.getTestOutputDirectory(), "TestRunner.mxml" );
        FileWriter fileWriter = new FileWriter( testSourceFile );
        IOUtils.write( sourceString, fileWriter );
        fileWriter.flush();
        fileWriter.close();
        return testSourceFile;
    }

    private InputStream getTemplate()
        throws MojoExecutionException
    {
        if ( testRunnerTemplate == null )
        {
            return getClass().getResourceAsStream( "/templates/test/TestRunner.vm" );
        }
        else if ( !testRunnerTemplate.exists() )
        {
            throw new MojoExecutionException( "Template file not found: " + testRunnerTemplate );
        }
        else
        {
            try
            {
                return new FileInputStream( testRunnerTemplate );
            }
            catch ( FileNotFoundException e )
            {
                // Never should happen
                throw new MojoExecutionException( "Error reading template file", e );
            }
        }
    }

    @Override
    protected void configure()
        throws MojoExecutionException, MojoFailureException
    {
        compiledLocales = getLocales();
        runtimeLocales = null;

        super.configure();

        // test launcher is at testOutputDirectory
        configuration.addSourcePath( new File[] { new File( build.getTestOutputDirectory() ) } );
        configuration.addSourcePath( getValidSourceRoots( project.getTestCompileSourceRoots() ).toArray( new File[0] ) );
        if ( getResource( compiledLocales[0] ) != null )
        {
            configuration.addSourcePath( new File[] { new File( resourceBundlePath ) } );
        }

        configuration.allowSourcePathOverlap( true );

        configuration.enableDebugging( true, super.debugPassword );
    }

    private File getResource( String locale )
    {
        try
        {
            return MavenUtils.getLocaleResourcePath( resourceBundlePath, locale );
        }
        catch ( MojoExecutionException e )
        {
            return null;
        }
    }

    @Override
    protected void resolveDependencies()
        throws MojoExecutionException, MojoFailureException
    {
        configuration.setExternalLibraryPath( getGlobalDependency() );

        // Set all dependencies as merged
        configuration.setLibraryPath( getDependenciesPath( "compile" ) );
        configuration.addLibraryPath( getDependenciesPath( "merged" ) );
        configuration.addLibraryPath( merge( getResourcesBundles( getDefaultLocale() ),
                                             getResourcesBundles( runtimeLocales ),
                                             getResourcesBundles( compiledLocales ) ) );

        // and add test libraries
        configuration.includeLibraries( merge( getDependenciesPath( "internal" ), getDependenciesPath( "test" ),
                                               getDependenciesPath( "rsl" ), getDependenciesPath( "caching" ),
                                               getDependenciesPath( "external" ) ) );

        configuration.setTheme( getThemes() );
    }

    private String[] getLocales()
    {
        if ( runtimeLocales == null && compiledLocales == null )
        {
            return new String[] { getDefaultLocale() };
        }
        Set<String> locales = new LinkedHashSet<String>();

        if ( runtimeLocales != null )
        {
            locales.addAll( Arrays.asList( runtimeLocales ) );
        }
        if ( compiledLocales != null )
        {
            locales.addAll( Arrays.asList( compiledLocales ) );
        }

        return locales.toArray( new String[0] );
    }

    private File[] merge( File[]... filesSets )
    {
        List<File> files = new ArrayList<File>();
        for ( File[] fileSet : filesSets )
        {
            files.addAll( Arrays.asList( fileSet ) );
        }
        return files.toArray( new File[0] );
    }

    @Override
    protected File getOutput()
    {
        return new File( build.getTestOutputDirectory(), "TestRunner.swf" );
    }

    @Override
    protected void compileModules()
        throws MojoFailureException, MojoExecutionException
    {
        // modules are ignored on unit tests
    }
    
    @Override
    protected void writeReport( Report report, String type )
        throws MojoExecutionException
    {
        // reports are ignored on unit tests
    }    

}
