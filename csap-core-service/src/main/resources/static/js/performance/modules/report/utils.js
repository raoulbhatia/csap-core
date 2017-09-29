define( ["model", "./dataService"], function ( model, dataService ) {

	 console.log( "Module loaded: reports/utils" );


	 return {
		  //
		  updateSampleCountDisplayed: function (numSamples) {
				updateSampleCountDisplayed(numSamples)  ;
		  },
		  getUserUrl: function( filter ) {
				return getUserUrl( filter ) ;
		  },
		  launchServiceJmxReport: function(serviceName) {
				launchServiceJmxReport(serviceName) ;
		  },
		  launchServiceOsReport: function(serviceName) {
				launchServiceOsReport(serviceName) 
		  }
		  //
	 }
	 
	 function launchServiceOsReport(serviceName) {
		  $( "#serviceDiv > table" ).hide();
		  $( "#serviceDetailDiv" ).show();

		  dataService.reset();
		  // buildServiceDetailReport( $(this).text() )
		  dataService.runReport( "service/detail", serviceName );
	 }


	 function launchServiceJmxReport(serviceName) {
		  dataService.reset();
		  $( "#jmxSummaryDiv" ).hide();
		  $( "#jmxDetailDiv" ).show();

		  // buildServiceDetailReport( $(this).text() )
		  console.log( "Launching custom jmx" );
		  dataService.runReport( "jmx/detail", serviceName );
	 }
	 
	 function updateSampleCountDisplayed(numSamples) {
		  var textSamples = numSamples;
		  if ( numSamples > 1000000 ) {
				textSamples = (numSamples / 1000000).toFixed( 1 ) + " Million"
		  } else if ( numSamples > 1000 ) {
				textSamples = (numSamples / 1000).toFixed( 1 ) + " Thousand"
		  }
		  $( "#sampleCount span" ).html( textSamples );
	 }
	 
	 	 // User table
	 function getUserUrl(eventFilter) {
		  var targetUrl = uiSettings.eventApiUrl + "/..?category=/csap/ui/*&" + eventFilter;

		  if ( !$( "#isAllProjects" ).is( ':checked' ) ) {
				targetUrl += "&project=" + model.getSelectedProject();
		  }

		  if ( !$( "#isAllLifes" ).is( ':checked' ) ) {
				targetUrl += "&life=" + $( "#lifeSelect" ).val();
		  }

		  if ( !$( "#isAllAppIds" ).is( ':checked' ) ) {
				targetUrl += "&appId=" + $( "#appIdFilterSelect" ).val();
		  }
		  return targetUrl
	 }
	 
	 
} ) ;